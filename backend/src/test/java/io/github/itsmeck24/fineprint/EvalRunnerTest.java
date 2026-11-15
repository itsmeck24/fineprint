package io.github.itsmeck24.fineprint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.Eligibility;
import io.github.itsmeck24.fineprint.rules.Hybrid;
import io.github.itsmeck24.fineprint.rules.LlmExtractor;
import io.github.itsmeck24.fineprint.rules.Rules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// writes what every extractor finds for every labeled posting, for eval/score.py to score.
// needs eval/data/<split>.jsonl from eval/export.py and a local ollama with the model pulled.
//   ./mvnw test -Peval -Deval.split=test -Deval.temperature=0 -Deval.seed=1
// model answers are cached under eval/data/llm-cache, so a rerun does not call the model again.
@Tag("eval")
class EvalRunnerTest {

    static final String SPLIT = System.getProperty("eval.split", "dev");
    static final double TEMPERATURE = Double.parseDouble(System.getProperty("eval.temperature", "0"));
    static final int SEED = Integer.getInteger("eval.seed", 1);
    static final String MODEL = System.getProperty("eval.model", "qwen3:14b");
    static final String OLLAMA = System.getProperty("eval.ollama", "http://localhost:11434");

    final JsonMapper json = JsonMapper.builder().build();
    final Path eval = Path.of("..", "eval");

    @Test
    void run() throws Exception {
        Map<String, JsonNode> postings = new HashMap<>();
        for (String line : Files.readAllLines(eval.resolve("data").resolve(SPLIT + ".jsonl"))) {
            JsonNode r = json.readTree(line);
            postings.put(r.path("key").asString(), r);
        }
        List<String> keys = Files.readAllLines(eval.resolve("labels").resolve(SPLIT + ".jsonl")).stream()
                .filter(l -> !l.isBlank()).map(l -> json.readTree(l).path("key").asString()).toList();
        LlmExtractor llm = new LlmExtractor(OLLAMA, MODEL, json);
        Path cache = eval.resolve("data").resolve("llm-cache");
        Files.createDirectories(cache);
        String run = SPLIT + "-t" + TEMPERATURE + "-s" + SEED;
        Path out = eval.resolve("data").resolve("predictions").resolve(run + ".jsonl");
        Files.createDirectories(out.getParent());

        StringBuilder lines = new StringBuilder();
        int done = 0;
        for (String key : keys) {
            JsonNode p = postings.get(key);
            if (p == null) {
                throw new IllegalStateException("labeled posting missing from export: " + key);
            }
            String title = p.path("title").asString();
            String body = p.path("body_text").asString();
            String form = p.path("form").isNull() || p.path("form").isMissingNode() ? null : json.writeValueAsString(p.path("form"));

            List<Criterion> rulesAll = Rules.extract(title, body, form, json);
            List<Criterion> rulesNoForm = Rules.extract(title, body, null, json);
            List<Criterion> titleOnly = Rules.extract(title, "", null, json);

            String cacheKey = sha(MODEL + "|" + LlmExtractor.PROMPT_VERSION + "|" + TEMPERATURE + "|" + SEED + "|" + title + "|" + body);
            Path cached = cache.resolve(cacheKey + ".json");
            String raw;
            long millis;
            if (Files.exists(cached)) {
                JsonNode c = json.readTree(Files.readString(cached));
                raw = c.path("raw").asString();
                millis = c.path("millis").asLong();
            } else {
                long t0 = System.nanoTime();
                raw = llm.ask(title, body, TEMPERATURE, SEED);
                millis = (System.nanoTime() - t0) / 1_000_000;
                ObjectNode c = json.createObjectNode().put("raw", raw).put("millis", millis);
                Files.writeString(cached, json.writeValueAsString(c));
            }
            LlmExtractor.Result lr = llm.parse(raw, title, body, millis);
            // the model only reads title and text, so form criteria come from the rules in every variant but title-only
            List<Criterion> formOnly = rulesAll.stream().filter(c -> c.source().equals("form")).toList();
            List<Criterion> llmAll = new java.util.ArrayList<>(lr.criteria());
            llmAll.addAll(formOnly);
            List<Criterion> hybrid = Hybrid.combine(rulesAll, lr.criteria());

            ObjectNode row = json.createObjectNode();
            row.put("key", key);
            row.put("llm_millis", millis);
            row.put("llm_dropped_quotes", lr.droppedQuotes());
            ObjectNode variants = row.putObject("variants");
            put(variants, "title_only", title, titleOnly);
            put(variants, "rules_text_only", title, rulesNoForm);
            put(variants, "rules", title, rulesAll);
            put(variants, "llm", title, llmAll);
            put(variants, "hybrid", title, hybrid);
            lines.append(json.writeValueAsString(row)).append('\n');
            done++;
            if (done % 10 == 0) {
                System.out.println(run + ": " + done + "/" + keys.size());
            }
        }
        Files.writeString(out, lines.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote " + out);
    }

    void put(ObjectNode variants, String name, String title, List<Criterion> criteria) {
        ObjectNode v = variants.putObject(name);
        ArrayNode cs = v.putArray("criteria");
        for (Criterion c : criteria) {
            ObjectNode o = cs.addObject();
            o.put("kind", c.kind());
            o.put("source", c.source());
            o.put("quote", c.quote());
            o.put("value", c.value());
            o.put("start", c.range() == null || c.range().start() == null ? null : c.range().start().toString());
            o.put("end", c.range() == null || c.range().end() == null ? null : c.range().end().toString());
            o.put("has_range", c.range() != null);
        }
        ObjectNode cards = v.putObject("cards");
        for (String degree : List.of("bachelor", "master", "phd")) {
            ObjectNode card = cards.putObject(degree);
            for (Eligibility.Rule r : Eligibility.card(new Eligibility.Profile(YearMonth.of(2027, 5), degree), title, criteria)) {
                card.putObject(r.kind()).put("label", r.label()).put("disagree", r.sourcesDisagree());
            }
        }
    }

    static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}

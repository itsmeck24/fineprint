package io.github.itsmeck24.fineprint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.RuleDiff;
import io.github.itsmeck24.fineprint.rules.Rules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// planted edits on copies of the test postings, scored with the same rules and diff the worker uses.
// wording edits must not count as a change, edits to a rule must.
//   ./mvnw test -Peval -Dtest=ChangeEvalTest
@Tag("eval")
class ChangeEvalTest {

    final JsonMapper json = JsonMapper.builder().build();
    final Path eval = Path.of("..", "eval");
    static final Pattern YEAR = Pattern.compile("\\b(20[2-3]\\d)\\b");

    record Case(String kind, boolean shouldChange, String body) {
    }

    @Test
    void run() throws Exception {
        Map<String, JsonNode> postings = new LinkedHashMap<>();
        for (String line : Files.readAllLines(eval.resolve("data").resolve("test.jsonl"))) {
            JsonNode r = json.readTree(line);
            postings.put(r.path("key").asString(), r);
        }
        Map<String, JsonNode> labels = new LinkedHashMap<>();
        for (String line : Files.readAllLines(eval.resolve("labels").resolve("test.jsonl"))) {
            if (!line.isBlank()) {
                JsonNode l = json.readTree(line);
                labels.put(l.path("key").asString(), l);
            }
        }
        Map<String, int[]> tally = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : labels.entrySet()) {
            JsonNode p = postings.get(e.getKey());
            String title = p.path("title").asString();
            String body = p.path("body_text").asString();
            String form = p.path("form").isNull() ? null : json.writeValueAsString(p.path("form"));
            List<Criterion> before = Rules.extract(title, body, form, json);
            for (Case c : cases(e.getValue(), body)) {
                List<Criterion> after = Rules.extract(title, c.body(), form, json);
                boolean changed = !RuleDiff.between(before, after).isEmpty();
                int[] t = tally.computeIfAbsent(c.kind(), k -> new int[2]);
                t[0]++;
                if (changed == c.shouldChange()) {
                    t[1]++;
                } else if (misses.size() < 20) {
                    misses.add(c.kind() + " " + e.getKey());
                }
            }
        }
        ObjectNode r = json.createObjectNode();
        r.put("command", "./mvnw test -Peval -Dtest=ChangeEvalTest");
        r.put("commit", CrashHarnessTest.git("rev-parse", "--short", "HEAD"));
        r.put("uncommitted_changes", !CrashHarnessTest.git("status", "--porcelain").isBlank());
        r.put("postings", labels.size());
        r.put("detector", "rules + RuleDiff, as in the worker");
        ObjectNode byKind = r.putObject("cases");
        for (Map.Entry<String, int[]> t : tally.entrySet()) {
            byKind.putObject(t.getKey()).put("cases", t.getValue()[0]).put("correct", t.getValue()[1])
                    .put("rate", Math.round(1000.0 * t.getValue()[1] / t.getValue()[0]) / 1000.0);
        }
        r.set("examples_wrong", json.valueToTree(misses));
        Path out = Path.of("..", "results");
        Files.createDirectories(out);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Files.writeString(out.resolve("change-detection-" + stamp + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(r) + "\n", StandardCharsets.UTF_8);
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(r));
    }

    List<Case> cases(JsonNode label, String body) {
        List<Case> out = new ArrayList<>();
        // wording only: a new opening line, an extra benefit, a changed pay figure
        out.add(new Case("wording_new_sentence", false, "We are growing fast and hiring across many teams.\n" + body));
        out.add(new Case("wording_benefit_line", false, body + "\nWe offer a monthly wellness stipend and free lunch."));
        out.add(new Case("wording_pay_change", false, body + "\nThe hourly rate for this role is $57 per hour."));
        // a real change to the graduation window: move the first year in the labeled sentence by one
        JsonNode g = label.path("grad_window");
        if (g.isObject() && g.path("source").asString().equals("text") && body.contains(g.path("quote").asString())) {
            String q = g.path("quote").asString();
            Matcher m = YEAR.matcher(q);
            if (m.find()) {
                String moved = q.substring(0, m.start()) + (Integer.parseInt(m.group(1)) + 1) + q.substring(m.end());
                out.add(new Case("graduation_year_moved", true, body.replace(q, moved)));
            }
        }
        // a real change to sponsorship: the posting starts saying it will not sponsor
        if (!label.path("work_auth").path("stance").asString().equals("no_sponsorship")) {
            out.add(new Case("sponsorship_withdrawn", true, body + "\nWe are unable to sponsor visas for this role."));
        }
        return out;
    }
}

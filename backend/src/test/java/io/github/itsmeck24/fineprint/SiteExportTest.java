package io.github.itsmeck24.fineprint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.Eligibility;
import io.github.itsmeck24.fineprint.rules.Rules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// writes the cards shown on the project site. they come from the same rules and card code as the app,
// run on a dated snapshot of public postings, for a few graduation months and degree levels.
//   ./mvnw test -Peval -Dtest=SiteExportTest
@Tag("eval")
class SiteExportTest {

    static final List<String> PICKS = List.of(
            "greenhouse:verkada:5210813007", "greenhouse:doordashusa:8204111", "greenhouse:cloudflare:8245197",
            "greenhouse:pinterest:7838591", "greenhouse:duolingo:8881612002", "greenhouse:scaleai:4730836005",
            "greenhouse:waymo:8223735", "lever:palantir:0a838e66", "greenhouse:mongodb:8209134",
            "greenhouse:affirm:8039471003", "greenhouse:roblox:8072244", "greenhouse:nuro:7351066");
    static final List<String> MONTHS = List.of("2026-12", "2027-05", "2027-12", "2028-05");
    static final List<String> DEGREES = List.of("bachelor", "master", "phd");

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void export() throws Exception {
        Map<String, JsonNode> all = new LinkedHashMap<>();
        for (String split : List.of("dev", "test")) {
            for (String line : Files.readAllLines(Path.of("..", "eval", "data", split + ".jsonl"))) {
                JsonNode r = json.readTree(line);
                all.put(r.path("key").asString(), r);
            }
        }
        ObjectNode out = json.createObjectNode();
        out.put("snapshot", "public Greenhouse and Lever postings collected on 2026-11-15");
        out.set("months", json.valueToTree(MONTHS));
        out.set("degrees", json.valueToTree(DEGREES));
        ArrayNode postings = out.putArray("postings");
        for (String pick : PICKS) {
            String key = all.keySet().stream().filter(k -> k.startsWith(pick)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("not in snapshot: " + pick));
            JsonNode p = all.get(key);
            String[] parts = key.split(":", 3);
            String title = p.path("title").asString();
            String form = p.path("form").isNull() ? null : json.writeValueAsString(p.path("form"));
            List<Criterion> criteria = Rules.extract(title, p.path("body_text").asString(), form, json);
            ObjectNode o = postings.addObject();
            o.put("company", parts[1]);
            o.put("ats", parts[0]);
            o.put("title", title.strip());
            o.put("url", parts[0].equals("greenhouse")
                    ? "https://job-boards.greenhouse.io/" + parts[1] + "/jobs/" + parts[2]
                    : "https://jobs.lever.co/" + parts[1] + "/" + parts[2]);
            o.put("hasForm", form != null);
            ObjectNode cards = o.putObject("cards");
            for (String m : MONTHS) {
                for (String d : DEGREES) {
                    cards.set(m + "|" + d, json.valueToTree(
                            Eligibility.card(new Eligibility.Profile(YearMonth.parse(m), d), title, criteria)));
                }
            }
        }
        Path target = Path.of("..", "web", "site", "public", "demo.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, json.writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("wrote " + target + " with " + postings.size() + " postings");
    }
}

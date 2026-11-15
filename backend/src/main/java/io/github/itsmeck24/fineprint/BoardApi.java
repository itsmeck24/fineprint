package io.github.itsmeck24.fineprint;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// talks to the public Greenhouse Job Board API and Lever Postings API.
// everything that comes back is untrusted text, it is only parsed and stored.
@Component
public class BoardApi {

    public record ListItem(String externalId, String internalJobId, String title, String url, Instant updatedAt) {
    }

    public record Snapshot(String externalId, String internalJobId, String title, String url, String location,
                           Instant firstPublished, Instant updatedAt, String bodyText, JsonNode form, JsonNode raw) {
    }

    public static class FetchFailed extends IOException {
        public final int status;

        FetchFailed(String url, int status) {
            super("HTTP " + status + " for " + url);
            this.status = status;
        }
    }

    static final String GREENHOUSE = "https://boards-api.greenhouse.io/v1/boards/";
    static final String LEVER = "https://api.lever.co/v0/postings/";

    private final HttpFetcher http;
    private final JsonMapper json;

    public BoardApi(HttpFetcher http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    public List<ListItem> greenhouseList(String token) throws IOException, InterruptedException {
        JsonNode root = getJson(GREENHOUSE + token + "/jobs");
        List<ListItem> out = new ArrayList<>();
        for (JsonNode j : root.path("jobs")) {
            out.add(new ListItem(j.path("id").asString(), str(j, "internal_job_id"), j.path("title").asString(),
                    str(j, "absolute_url"), time(str(j, "updated_at"))));
        }
        return out;
    }

    public Snapshot greenhouseDetail(String token, String id) throws IOException, InterruptedException {
        return parseGreenhouseDetail(getJson(GREENHOUSE + token + "/jobs/" + id + "?questions=true"));
    }

    public List<Snapshot> leverList(String site) throws IOException, InterruptedException {
        JsonNode root = getJson(LEVER + site + "?mode=json");
        List<Snapshot> out = new ArrayList<>();
        for (JsonNode p : root) {
            out.add(parseLeverPosting(p));
        }
        return out;
    }

    Snapshot parseGreenhouseDetail(JsonNode d) {
        String body = PostingText.greenhouseContentToText(str(d, "content"));
        return new Snapshot(d.path("id").asString(), str(d, "internal_job_id"), d.path("title").asString(),
                str(d, "absolute_url"), str(d.path("location"), "name"), time(str(d, "first_published")),
                time(str(d, "updated_at")), body, greenhouseForm(d.path("questions")), d);
    }

    Snapshot parseLeverPosting(JsonNode p) {
        StringBuilder html = new StringBuilder(nz(str(p, "description")));
        for (JsonNode list : p.path("lists")) {
            html.append("<h3>").append(nz(str(list, "text"))).append("</h3>").append(nz(str(list, "content")));
        }
        html.append(nz(str(p, "additional")));
        Instant created = p.hasNonNull("createdAt") ? Instant.ofEpochMilli(p.get("createdAt").asLong()) : null;
        return new Snapshot(p.path("id").asString(), null, p.path("text").asString(), str(p, "hostedUrl"),
                str(p.path("categories"), "location"), created, null,
                PostingText.htmlToText(html.toString()), null, p);
    }

    // keep only what the form asks and which answers it offers, in the order the board gives them
    JsonNode greenhouseForm(JsonNode questions) {
        if (!questions.isArray()) {
            return null;
        }
        ArrayNode out = json.createArrayNode();
        for (JsonNode q : questions) {
            ObjectNode item = out.addObject();
            item.put("label", nz(str(q, "label")).strip());
            item.put("required", q.path("required").asBoolean(false));
            ArrayNode options = item.putArray("options");
            for (JsonNode f : q.path("fields")) {
                for (JsonNode v : f.path("values")) {
                    options.add(nz(str(v, "label")));
                }
            }
        }
        return out;
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpFetcher.Response res = http.get(url);
        if (res.status() != 200) {
            throw new FetchFailed(url, res.status());
        }
        return json.readTree(res.body());
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Instant time(String s) {
        return s == null ? null : OffsetDateTime.parse(s).toInstant();
    }
}

package io.github.itsmeck24.fineprint.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.itsmeck24.fineprint.DbTest;
import io.github.itsmeck24.fineprint.FakeBoards;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// the whole path over real http: register, save a link, the worker fetches it from a fake board,
// rules run, and the card comes back. postgres is real, the job board is fake.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class ApiFlowTest extends DbTest {

    static final FakeBoards boards = new FakeBoards();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("fineprint.sources.greenhouse-base", () -> boards.base() + "/v1/boards/");
        r.add("fineprint.sources.lever-base", () -> boards.base() + "/v0/postings/");
        r.add("fineprint.worker.enabled", () -> "true");
        r.add("fineprint.worker.idle-sleep", () -> "50ms");
        r.add("fineprint.collector.min-gap", () -> "0s");
    }

    @Autowired
    Environment env;

    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    String api;

    @BeforeEach
    void setUp() {
        api = "http://localhost:" + env.getProperty("local.server.port") + "/api";
        String questions = """
                [{"label": "When do you expect to graduate?", "required": true,
                  "fields": [{"name": "q1", "values": [{"label": "Sept - Dec 2027"}, {"label": "Jan - April 2028"},
                                                      {"label": "Other"}]}]},
                 {"label": "Will you require sponsorship now or in the future?", "required": true,
                  "fields": [{"name": "q2", "values": [{"label": "Yes"}, {"label": "No"}]}]}]""";
        boards.put("/v1/boards/acme/jobs/111?questions=true", 200, FakeBoards.greenhouseDetail(111,
                "Backend Software Engineering Intern 2027",
                "<p>About us</p><ul><li>Pursuing a degree and graduating by June 2028</li></ul>", questions));
    }

    record Res(int status, JsonNode body) {
    }

    Res call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isBlank() ? null : json.readTree(r.body()));
    }

    String register(String email) throws Exception {
        Res r = call("POST", "/auth/register", null,
                "{\"email\":\"" + email + "\",\"password\":\"correct horse\",\"gradMonth\":\"2027-05\",\"degreeLevel\":\"master\"}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().path("token").asString();
    }

    JsonNode waitForCard(String token, long id) throws Exception {
        Instant until = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(until)) {
            Res r = call("GET", "/postings/" + id, token, null);
            if (r.status() == 200 && r.body().path("ready").asBoolean()) {
                return r.body();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("card never became ready");
    }

    JsonNode rule(JsonNode card, String kind) {
        for (JsonNode r : card.path("rules")) {
            if (r.path("kind").asString().equals(kind)) {
                return r;
            }
        }
        throw new AssertionError("no rule " + kind);
    }

    @Test
    void saveAPostingAndReadItsCard() throws Exception {
        String a = register("a@example.test");
        Res saved = call("POST", "/postings", a, "{\"url\":\"https://job-boards.greenhouse.io/acme/jobs/111\"}");
        assertThat(saved.status()).isEqualTo(202);
        long id = saved.body().path("id").asLong();

        JsonNode card = waitForCard(a, id);
        assertThat(card.path("title").asString()).isEqualTo("Backend Software Engineering Intern 2027");

        JsonNode grad = rule(card, "grad_window");
        assertThat(grad.path("label").asString()).isEqualTo("matches");
        assertThat(grad.path("sourcesDisagree").asBoolean()).isTrue();
        assertThat(grad.path("reasons").get(0).path("quote").asString())
                .isEqualTo("Pursuing a degree and graduating by June 2028");
        assertThat(rule(card, "work_auth").path("label").asString()).isEqualTo("screened");

        // saving the same link again does not make a second posting
        Res again = call("POST", "/postings", a, "{\"url\":\"https://job-boards.greenhouse.io/acme/jobs/111/\"}");
        assertThat(again.body().path("id").asLong()).isEqualTo(id);
        assertThat(call("GET", "/postings", a, null).body().size()).isEqualTo(1);

        String lookup = URLEncoder.encode("https://job-boards.greenhouse.io/acme/jobs/111", StandardCharsets.UTF_8);
        Res found = call("GET", "/postings/lookup?url=" + lookup, a, null);
        assertThat(found.body().path("known").asBoolean()).isTrue();
        assertThat(found.body().path("saved").asBoolean()).isTrue();
    }

    @Test
    void usersOnlySeeTheirOwnPostings() throws Exception {
        String a = register("owner@example.test");
        String b = register("other@example.test");
        long id = call("POST", "/postings", a, "{\"url\":\"https://job-boards.greenhouse.io/acme/jobs/111\"}")
                .body().path("id").asLong();
        assertThat(call("GET", "/postings/" + id, b, null).status()).isEqualTo(404);
        assertThat(call("DELETE", "/postings/" + id, b, null).status()).isEqualTo(404);
        assertThat(call("GET", "/postings", b, null).body().size()).isZero();
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() throws Exception {
        assertThat(call("GET", "/postings", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/postings", "not.a.token", null).status()).isEqualTo(401);
    }

    @Test
    void badInputIsRejected() throws Exception {
        String a = register("input@example.test");
        assertThat(call("POST", "/postings", a, "{\"url\":\"https://example.com/careers/123\"}").status()).isEqualTo(400);
        assertThat(call("POST", "/postings", a, "{\"url\":\"https://job-boards.greenhouse.io/../jobs/1\"}").status())
                .isEqualTo(400);
        assertThat(call("POST", "/auth/register", null,
                "{\"email\":\"x@example.test\",\"password\":\"short\",\"gradMonth\":\"2027-05\",\"degreeLevel\":\"master\"}")
                .status()).isEqualTo(400);
        assertThat(call("POST", "/auth/register", null,
                "{\"email\":\"input@example.test\",\"password\":\"long enough\",\"gradMonth\":\"2027-05\",\"degreeLevel\":\"master\"}")
                .status()).isEqualTo(409);
    }

    @Test
    void deletingTheAccountRemovesItsData() throws Exception {
        String a = register("leaving@example.test");
        call("POST", "/postings", a, "{\"url\":\"https://job-boards.greenhouse.io/acme/jobs/111\"}");
        assertThat(call("DELETE", "/me", a, null).status()).isEqualTo(204);
        assertThat(call("GET", "/me", a, null).status()).isEqualTo(401);
        assertThat(call("POST", "/auth/login", null, "{\"email\":\"leaving@example.test\",\"password\":\"correct horse\"}")
                .status()).isEqualTo(401);
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
        register("login@example.test");
        Res wrong = call("POST", "/auth/login", null, "{\"email\":\"login@example.test\",\"password\":\"nope nope\"}");
        Res unknown = call("POST", "/auth/login", null, "{\"email\":\"nobody@example.test\",\"password\":\"nope nope\"}");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
    }
}

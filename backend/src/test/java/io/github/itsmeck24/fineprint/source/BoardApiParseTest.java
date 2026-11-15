package io.github.itsmeck24.fineprint.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

// hand-written samples in the shape the two apis return, no real posting text
class BoardApiParseTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final BoardApi api = new BoardApi(null, json, null);

    @Test
    void greenhouseDetailWithForm() {
        String body = """
                {"id": 123, "internal_job_id": 456, "title": "Software Engineer Intern (2027)",
                 "absolute_url": "https://example.com/jobs?gh_jid=123",
                 "location": {"name": "Remote"},
                 "first_published": "2026-09-01T10:00:00-04:00",
                 "updated_at": "2026-11-01T09:30:00-05:00",
                 "content": "&lt;p&gt;Graduating by June 2028&lt;/p&gt;",
                 "questions": [
                   {"label": "Expected graduation date ", "required": true,
                    "fields": [{"name": "q1", "type": "multi_value_single_select",
                                "values": [{"label": "Sept - Dec 2027", "value": 1}, {"label": "Other", "value": 2}]}]},
                   {"label": "Resume", "required": false, "fields": [{"name": "resume", "type": "input_file"}]}
                 ]}""";
        BoardApi.Snapshot s = api.parseGreenhouseDetail(json.readTree(body));

        assertThat(s.externalId()).isEqualTo("123");
        assertThat(s.internalJobId()).isEqualTo("456");
        assertThat(s.location()).isEqualTo("Remote");
        assertThat(s.firstPublished()).isEqualTo(Instant.parse("2026-09-01T14:00:00Z"));
        assertThat(s.updatedAt()).isEqualTo(Instant.parse("2026-11-01T14:30:00Z"));
        assertThat(s.bodyText()).isEqualTo("Graduating by June 2028");
        assertThat(json.writeValueAsString(s.form())).isEqualTo(
                "[{\"label\":\"Expected graduation date\",\"required\":true,\"options\":[\"Sept - Dec 2027\",\"Other\"]},"
                        + "{\"label\":\"Resume\",\"required\":false,\"options\":[]}]");
    }

    @Test
    void greenhouseDetailWithoutQuestionsHasNoForm() {
        BoardApi.Snapshot s = api.parseGreenhouseDetail(json.readTree(
                "{\"id\": 1, \"title\": \"Intern\", \"content\": null, \"location\": null}"));
        assertThat(s.form()).isNull();
        assertThat(s.bodyText()).isEmpty();
        assertThat(s.location()).isNull();
    }

    @Test
    void leverPostingJoinsDescriptionListsAndAdditional() {
        String body = """
                {"id": "abc-1", "text": "Data Science Intern [Summer 2027]",
                 "hostedUrl": "https://jobs.lever.co/example/abc-1",
                 "createdAt": 1762470857184,
                 "categories": {"location": "Foster City, CA"},
                 "description": "<div>Intro</div>",
                 "lists": [{"text": "Qualifications", "content": "<li>Expected to graduate between Fall 2027 and Summer 2028</li>"}],
                 "additional": "<div>Pay range</div>"}""";
        BoardApi.Snapshot s = api.parseLeverPosting(json.readTree(body));

        assertThat(s.externalId()).isEqualTo("abc-1");
        assertThat(s.location()).isEqualTo("Foster City, CA");
        assertThat(s.firstPublished()).isEqualTo(Instant.ofEpochMilli(1762470857184L));
        assertThat(s.form()).isNull();
        assertThat(s.bodyText()).isEqualTo("""
                Intro
                Qualifications
                Expected to graduate between Fall 2027 and Summer 2028
                Pay range""");
    }
}

package io.github.itsmeck24.fineprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

// runs against a real postgres in docker, with the flyway migrations applied
@SpringBootTest(properties = "fineprint.collector.enabled=false")
@Testcontainers
class PostingStoreTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    PostingStore store;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CollectorProperties props;

    private final JsonMapper json = JsonMapper.builder().build();

    private BoardApi.Snapshot snap(String externalId, String body) {
        return new BoardApi.Snapshot(externalId, null, "SWE Intern", null, "SF", Instant.parse("2026-09-01T00:00:00Z"),
                null, body, null, json.readTree("{}"));
    }

    private int versions(long postingId) {
        return jdbc.sql("select count(*) from posting_versions where posting_id = ?").param(postingId)
                .query(Integer.class).single();
    }

    @Test
    void sameContentTwiceStoresOneVersion() {
        long board = store.boardId("greenhouse", "test-a");
        long posting = store.upsertPosting(board, "1", null, "SWE Intern", null, null);

        assertThat(store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}")).isTrue();
        assertThat(store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}")).isFalse();
        assertThat(versions(posting)).isEqualTo(1);
    }

    @Test
    void changingBackToOldContentIsStillAVersion() {
        long board = store.boardId("greenhouse", "test-b");
        long posting = store.upsertPosting(board, "1", null, "SWE Intern", null, null);

        store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}");
        store.saveVersionIfChanged(posting, snap("1", "body B"), null, "{}");
        store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}");
        assertThat(versions(posting)).isEqualTo(3);
    }

    @Test
    void upsertKeepsOneRowPerPostingAndFirstPublishedSticks() {
        long board = store.boardId("lever", "test-c");
        assertThat(store.boardId("lever", "test-c")).isEqualTo(board);

        long first = store.upsertPosting(board, "x", null, "Old title", null, null);
        long second = store.upsertPosting(board, "x", null, "New title", null, null);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.sql("select title from postings where id = ?").param(first).query(String.class).single())
                .isEqualTo("New title");

        store.saveVersionIfChanged(first, snap("x", "v1"), null, "{}");
        BoardApi.Snapshot later = new BoardApi.Snapshot("x", null, "New title", null, "SF",
                Instant.parse("2030-01-01T00:00:00Z"), null, "v2", null, json.readTree("{}"));
        store.saveVersionIfChanged(first, later, null, "{}");
        assertThat(jdbc.sql("select first_published_at from postings where id = ?").param(first)
                .query(java.sql.Timestamp.class).single().toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void titleFilterKeepsEarlyCareerRolesOnly() {
        Collector c = new Collector(props, null, null, json);
        assertThat(c.wanted("Software Engineering Intern (2027)")).isTrue();
        assertThat(c.wanted("Backend Software Engineer - University Graduate 2027")).isTrue();
        assertThat(c.wanted("[2027] Software Engineer, Early Career")).isTrue();
        assertThat(c.wanted("Forward Deployed Software Engineer, Internship")).isTrue();
        assertThat(c.wanted("Engineering Manager - Internal Systems Automations Platform")).isFalse();
        assertThat(c.wanted("International Payroll Analyst")).isFalse();
        assertThat(c.wanted("Senior Software Engineer")).isFalse();
        assertThat(c.wanted("Senior Manager, Internal Comms")).isFalse();
    }
}

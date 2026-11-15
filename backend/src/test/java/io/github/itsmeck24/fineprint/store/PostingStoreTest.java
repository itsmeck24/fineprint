package io.github.itsmeck24.fineprint.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.itsmeck24.fineprint.DbTest;
import io.github.itsmeck24.fineprint.queue.PollBoardJob;
import io.github.itsmeck24.fineprint.source.BoardApi;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostingStoreTest extends DbTest {

    @Autowired
    PostingStore store;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PollBoardJob poll;

    private final JsonMapper json = JsonMapper.builder().build();

    private BoardApi.Snapshot snap(String externalId, String body) {
        return new BoardApi.Snapshot(externalId, null, "SWE Intern", null, "SF", Instant.parse("2026-09-01T00:00:00Z"),
                null, body, null, json.readTree("{}"));
    }

    private int versions(long postingId) {
        return jdbc.sql("select count(*) from posting_versions where posting_id = ?").param(postingId)
                .query(Integer.class).single();
    }

    private int extractJobs(long versionId) {
        return jdbc.sql("select count(*) from jobs where type = 'extract' and payload->>'versionId' = ?")
                .param(Long.toString(versionId)).query(Integer.class).single();
    }

    @Test
    void sameContentTwiceStoresOneVersion() {
        long board = store.boardId("greenhouse", "store-a");
        long posting = store.upsertPosting(board, "1", null, "SWE Intern", null);

        assertThat(store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}")).isPresent();
        assertThat(store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}")).isEmpty();
        assertThat(versions(posting)).isEqualTo(1);
    }

    @Test
    void changingBackToOldContentIsStillAVersion() {
        long board = store.boardId("greenhouse", "store-b");
        long posting = store.upsertPosting(board, "1", null, "SWE Intern", null);

        store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}");
        store.saveVersionIfChanged(posting, snap("1", "body B"), null, "{}");
        store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}");
        assertThat(versions(posting)).isEqualTo(3);
    }

    @Test
    void everyNewVersionGetsExactlyOneExtractJob() {
        long board = store.boardId("greenhouse", "store-c");
        long posting = store.upsertPosting(board, "1", null, "SWE Intern", null);
        long v = store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}").orElseThrow();
        store.saveVersionIfChanged(posting, snap("1", "body A"), null, "{}");
        assertThat(extractJobs(v)).isEqualTo(1);
    }

    @Test
    void upsertKeepsOneRowPerPostingAndFirstPublishedSticks() {
        long board = store.boardId("lever", "store-d");
        assertThat(store.boardId("lever", "store-d")).isEqualTo(board);

        long first = store.upsertPosting(board, "x", null, "Old title", null);
        long second = store.upsertPosting(board, "x", null, "New title", null);
        assertThat(second).isEqualTo(first);

        store.saveVersionIfChanged(first, snap("x", "v1"), null, "{}");
        BoardApi.Snapshot later = new BoardApi.Snapshot("x", null, "New title", null, "SF",
                Instant.parse("2030-01-01T00:00:00Z"), null, "v2", null, json.readTree("{}"));
        store.saveVersionIfChanged(first, later, null, "{}");
        assertThat(jdbc.sql("select first_published_at from postings where id = ?").param(first)
                .query(java.sql.Timestamp.class).single().toInstant())
                .isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void ensurePostingDoesNotTouchAnExistingRow() {
        long board = store.boardId("greenhouse", "store-e");
        long id = store.upsertPosting(board, "9", null, "Real title", null);
        assertThat(store.ensurePosting(board, "9")).isEqualTo(id);
        assertThat(jdbc.sql("select title from postings where id = ?").param(id).query(String.class).single())
                .isEqualTo("Real title");
    }

    @Test
    void titleFilterKeepsEarlyCareerRolesOnly() {
        assertThat(poll.wanted("Software Engineering Intern (2027)")).isTrue();
        assertThat(poll.wanted("Backend Software Engineer - University Graduate 2027")).isTrue();
        assertThat(poll.wanted("[2027] Software Engineer, Early Career")).isTrue();
        assertThat(poll.wanted("Forward Deployed Software Engineer, Internship")).isTrue();
        assertThat(poll.wanted("Engineering Manager - Internal Systems Automations Platform")).isFalse();
        assertThat(poll.wanted("International Payroll Analyst")).isFalse();
        assertThat(poll.wanted("Senior Software Engineer")).isFalse();
        assertThat(poll.wanted("Senior Manager, Internal Comms")).isFalse();
    }
}

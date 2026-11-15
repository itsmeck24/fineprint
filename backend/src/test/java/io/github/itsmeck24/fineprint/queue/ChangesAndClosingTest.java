package io.github.itsmeck24.fineprint.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.itsmeck24.fineprint.DbTest;
import io.github.itsmeck24.fineprint.FakeBoards;
import io.github.itsmeck24.fineprint.source.BoardApi;
import io.github.itsmeck24.fineprint.store.ChangeStore;
import io.github.itsmeck24.fineprint.store.PostingStore;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChangesAndClosingTest extends DbTest {

    static final FakeBoards boards = new FakeBoards();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("fineprint.sources.greenhouse-base", () -> boards.base() + "/v1/boards/");
        r.add("fineprint.collector.min-gap", () -> "0s");
    }

    @Autowired
    JobQueue queue;
    @Autowired
    List<JobHandler> handlers;
    @Autowired
    PostingStore store;
    @Autowired
    ChangeStore changes;
    @Autowired
    Jobs jobs;
    @Autowired
    JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        jdbc.sql("delete from jobs").update();
    }

    void drain() throws Exception {
        Drain.all(queue, handlers, json);
    }

    String detail(long id, String body) {
        return FakeBoards.greenhouseDetail(id, "Software Engineer Intern", body, "[]");
    }

    String list(long... ids) {
        StringBuilder sb = new StringBuilder("{\"jobs\":[");
        for (int i = 0; i < ids.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":").append(ids[i])
                    .append(",\"title\":\"Software Engineer Intern\",\"updated_at\":\"2026-11-01T09:00:00-04:00\"}");
        }
        return sb.append("]}").toString();
    }

    List<String> kinds(long postingId) {
        return changes.forPosting(postingId).stream().map(ChangeStore.Event::kind).toList();
    }

    String status(long postingId) {
        return jdbc.sql("select status from postings where id = ?").param(postingId).query(String.class).single();
    }

    @Test
    void windowChangeIsOneEventAndWordingChangeIsNone() throws Exception {
        long board = store.boardId("greenhouse", "chg");
        long posting = store.ensurePosting(board, "501");
        boards.put("/v1/boards/chg/jobs/501?questions=true", 200, detail(501, "<p>You will graduate in fall 2027 or spring 2028.</p>"));
        jobs.fetchPosting(posting);
        drain();
        assertThat(kinds(posting)).isEmpty();

        boards.put("/v1/boards/chg/jobs/501?questions=true", 200, detail(501, "<p>You will graduate in spring 2028 or summer 2028.</p>"));
        jobs.fetchPosting(posting);
        drain();
        List<ChangeStore.Event> events = changes.forPosting(posting);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).kind()).isEqualTo("rules_changed");
        assertThat(events.get(0).summary()).contains("spring 2028 or summer 2028").contains("fall 2027 or spring 2028");

        // new wording, same rules: a new version but no event
        boards.put("/v1/boards/chg/jobs/501?questions=true", 200,
                detail(501, "<p>We are hiring!</p><p>You will graduate in spring 2028 or summer 2028.</p>"));
        jobs.fetchPosting(posting);
        drain();
        assertThat(jdbc.sql("select count(*) from posting_versions where posting_id = ?").param(posting)
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(changes.forPosting(posting)).hasSize(1);
    }

    // workers can extract versions in any order. each adjacent pair must be reported exactly once.
    @Test
    void outOfOrderAndRepeatedExtractionGivesEachChangeOnce() throws Exception {
        long board = store.boardId("greenhouse", "order");
        long posting = store.ensurePosting(board, "1");
        List<Long> v = new ArrayList<>();
        for (String body : List.of("Graduating in 2027.", "Graduating in 2028.", "Graduating in 2029.")) {
            BoardApi.Snapshot s = new BoardApi.Snapshot("1", null, "Intern", null, null, null, null, body, null,
                    json.readTree("{}"));
            v.add(store.saveVersionIfChanged(posting, s, null, "{}").orElseThrow());
        }
        jdbc.sql("delete from jobs").update();
        ExtractJob extract = handlers.stream().filter(h -> h instanceof ExtractJob).map(h -> (ExtractJob) h)
                .findFirst().orElseThrow();
        for (long id : List.of(v.get(2), v.get(0), v.get(1), v.get(1), v.get(2))) {
            extract.run(json.readTree("{\"versionId\":" + id + "}"));
        }
        List<ChangeStore.Event> events = changes.forPosting(posting);
        assertThat(events).extracting(e -> e.fromVersionId() + ">" + e.toVersionId())
                .containsExactlyInAnyOrder(v.get(0) + ">" + v.get(1), v.get(1) + ">" + v.get(2));
    }

    @Test
    void closedOnlyAfterTwoMissesAnHourApartThenReopens() throws Exception {
        long board = store.boardId("greenhouse", "close");
        boards.put("/v1/boards/close/jobs", 200, list(700));
        boards.put("/v1/boards/close/jobs/700?questions=true", 200, detail(700, "<p>Hello</p>"));
        jobs.pollBoard(board);
        drain();
        long posting = store.find(board, "700").orElseThrow().id();
        assertThat(status(posting)).isEqualTo("open");

        // it drops off the list and its page 404s
        boards.put("/v1/boards/close/jobs", 200, list());
        boards.put("/v1/boards/close/jobs/700?questions=true", 404, "{}");
        jobs.pollBoard(board);
        drain();
        assertThat(status(posting)).isEqualTo("open");
        assertThat(kinds(posting)).isEmpty();

        // an hour later the follow-up check runs and it is still gone
        jdbc.sql("update postings set first_miss_at = now() - interval '2 hours' where id = ?").param(posting).update();
        jdbc.sql("update jobs set run_at = now() where status = 'ready'").update();
        drain();
        assertThat(status(posting)).isEqualTo("closed");
        assertThat(kinds(posting)).containsExactly("closed");

        // repeating checks does not record a second close
        jobs.checkPosting(posting);
        drain();
        assertThat(kinds(posting)).containsExactly("closed");

        // it comes back
        boards.put("/v1/boards/close/jobs", 200, list(700));
        boards.put("/v1/boards/close/jobs/700?questions=true", 200, detail(700, "<p>Hello</p>"));
        jobs.pollBoard(board);
        drain();
        assertThat(status(posting)).isEqualTo("open");
        assertThat(kinds(posting)).containsExactly("closed", "reopened");
    }

    @Test
    void serverErrorsNeverClose() throws Exception {
        long board = store.boardId("greenhouse", "flaky");
        long posting = store.ensurePosting(board, "800");
        boards.put("/v1/boards/flaky/jobs/800?questions=true", 503, "{}");
        for (int i = 0; i < 5; i++) {
            jobs.checkPosting(posting, java.time.Duration.ZERO, "flaky-" + i);
            drain();
            jdbc.sql("update jobs set run_at = now() where status = 'ready'").update();
        }
        assertThat(status(posting)).isEqualTo("unreachable");
        assertThat(kinds(posting)).isEmpty();
    }

    @Test
    void oneListingMissIsNotEnough() throws Exception {
        long board = store.boardId("greenhouse", "blip");
        boards.put("/v1/boards/blip/jobs", 200, list(900));
        boards.put("/v1/boards/blip/jobs/900?questions=true", 200, detail(900, "<p>Hi</p>"));
        jobs.pollBoard(board);
        drain();
        long posting = store.find(board, "900").orElseThrow().id();

        // a single bad listing: the page itself still answers 200
        boards.put("/v1/boards/blip/jobs", 200, list());
        jobs.pollBoard(board);
        drain();
        assertThat(status(posting)).isEqualTo("open");
        assertThat(jdbc.sql("select miss_count from postings where id = ?").param(posting).query(Integer.class).single())
                .isZero();
    }

    // found by the 500-kill run: the list says a posting changed, but the fetch that ran got the old content
    // (it was already running when the change appeared). the next poll must still fetch it again.
    @Test
    void aChangeSeenOnlyInTheListingIsFetchedLater() throws Exception {
        long board = store.boardId("greenhouse", "stale");
        String gen1 = "{\"jobs\":[{\"id\":600,\"title\":\"Software Engineer Intern\",\"updated_at\":\"2026-11-01T09:00:00-04:00\"}]}";
        String gen2 = gen1.replace("2026-11-01T09:00:00", "2026-11-02T09:00:00");
        boards.put("/v1/boards/stale/jobs", 200, gen1);
        boards.put("/v1/boards/stale/jobs/600?questions=true", 200, detail(600, "<p>You will graduate in fall 2027 or spring 2028.</p>"));
        jobs.pollBoard(board);
        drain();
        long posting = store.find(board, "600").orElseThrow().id();

        // the listing moves on, the detail answer is still the old one
        boards.put("/v1/boards/stale/jobs", 200, gen2);
        jobs.pollBoard(board);
        drain();
        // now the detail catches up
        boards.put("/v1/boards/stale/jobs/600?questions=true", 200, FakeBoards.greenhouseDetail(600, "Software Engineer Intern",
                "<p>You will graduate in spring 2028 or summer 2028.</p>", "[]").replace("2026-11-01T09:00:00", "2026-11-02T09:00:00"));
        jobs.pollBoard(board);
        drain();
        assertThat(kinds(posting)).containsExactly("rules_changed");
    }

    @Test
    void twoWritersSavingTheSameNewContentStoreOneVersion() throws Exception {
        long board = store.boardId("greenhouse", "race");
        long posting = store.ensurePosting(board, "1");
        BoardApi.Snapshot s = new BoardApi.Snapshot("1", null, "Intern", null, null, null, null, "same", null,
                json.readTree("{}"));
        Set<Boolean> results = java.util.Collections.synchronizedSet(new HashSet<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(Thread.ofPlatform().start(() -> results.add(store.saveVersionIfChanged(posting, s, null, "{}").isPresent())));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertThat(jdbc.sql("select count(*) from posting_versions where posting_id = ?").param(posting)
                .query(Integer.class).single()).isEqualTo(1);
    }
}

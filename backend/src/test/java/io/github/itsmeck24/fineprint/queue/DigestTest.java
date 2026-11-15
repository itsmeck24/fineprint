package io.github.itsmeck24.fineprint.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.itsmeck24.fineprint.DbTest;
import io.github.itsmeck24.fineprint.FakeBoards;
import io.github.itsmeck24.fineprint.store.PostingStore;
import io.github.itsmeck24.fineprint.store.UserStore;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DigestTest extends DbTest {

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
    PostingStore postings;
    @Autowired
    UserStore users;
    @Autowired
    Jobs jobs;
    @Autowired
    JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        jdbc.sql("delete from jobs").update();
    }

    void list(String token, long... ids) {
        StringBuilder sb = new StringBuilder("{\"jobs\":[");
        for (int i = 0; i < ids.length; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":").append(ids[i])
                    .append(",\"title\":\"Software Engineer Intern\",\"updated_at\":\"2026-11-01T09:00:00-04:00\"}");
        }
        boards.put("/v1/boards/" + token + "/jobs", 200, sb.append("]}").toString());
    }

    void detail(String token, long id, String body) {
        boards.put("/v1/boards/" + token + "/jobs/" + id + "?questions=true", 200,
                FakeBoards.greenhouseDetail(id, "Software Engineer Intern", "<p>" + body + "</p>", "[]"));
    }

    int items(long userId) {
        return jdbc.sql("select count(*) from digest_items where user_id = ?").param(userId).query(Integer.class).single();
    }

    @Test
    void newPostingsThatDoNotRuleYouOutGoIntoOneDigest() throws Exception {
        long board = postings.boardId("greenhouse", "watched");
        // one posting already on the board before the watch
        list("watched", 1);
        detail("watched", 1, "Graduating in 2027.");
        jobs.pollBoard(board);
        Drain.all(queue, handlers, json);

        long user = users.create("watcher@example.test", "x", YearMonth.of(2027, 5), "master").orElseThrow();
        jdbc.sql("insert into watches (user_id, board_id, created_at) values (?, ?, now())").params(user, board).update();

        // two new ones: one fits, one is ruled out by its window
        list("watched", 1, 2, 3);
        detail("watched", 2, "You will graduate in spring 2027 or summer 2027.");
        detail("watched", 3, "You will graduate in fall 2027 or spring 2028.");
        jobs.pollBoard(board);
        Drain.all(queue, handlers, json);
        assertThat(items(user)).isEqualTo(1);

        jobs.digests();
        Drain.all(queue, handlers, json);
        jobs.digests();
        Drain.all(queue, handlers, json);
        List<String> subjects = jdbc.sql("select subject from notifications where user_id = ? and kind = 'digest'")
                .param(user).query(String.class).list();
        assertThat(subjects).containsExactly("1 new posting you can apply to");
        assertThat(jdbc.sql("select count(*) from digest_items where user_id = ? and notification_id is null")
                .param(user).query(Integer.class).single()).isZero();
    }
}

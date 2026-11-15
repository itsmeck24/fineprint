package io.github.itsmeck24.fineprint.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.itsmeck24.fineprint.DbTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JobQueueTest extends DbTest {

    @Autowired
    JobQueue queue;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("delete from jobs").update();
    }

    private String status(long id) {
        return jdbc.sql("select status from jobs where id = ?").param(id).query(String.class).single();
    }

    @Test
    void sameDedupKeyIsQueuedOnceUntilItFinishes() {
        assertThat(queue.enqueue("t", "{}", "k1")).isTrue();
        assertThat(queue.enqueue("t", "{}", "k1")).isFalse();
        JobQueue.Job job = queue.claim("w", Duration.ofMinutes(1)).orElseThrow();
        assertThat(queue.enqueue("t", "{}", "k1")).isFalse();
        queue.complete(job.id(), "w");
        assertThat(queue.enqueue("t", "{}", "k1")).isTrue();
    }

    @Test
    void twoWorkersNeverClaimTheSameJob() throws Exception {
        for (int i = 0; i < 40; i++) {
            queue.enqueue("t", "{}", "many-" + i);
        }
        List<Long> claimed = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            String name = "w" + w;
            threads.add(Thread.ofPlatform().start(() -> {
                Optional<JobQueue.Job> j;
                while ((j = queue.claim(name, Duration.ofMinutes(1))).isPresent()) {
                    claimed.add(j.get().id());
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        Set<Long> unique = new HashSet<>(claimed);
        assertThat(claimed).hasSize(40);
        assertThat(unique).hasSize(40);
    }

    @Test
    void expiredLeaseIsTakenOverAndTheOldWorkerCannotFinish() throws Exception {
        queue.enqueue("t", "{}", "lease");
        JobQueue.Job first = queue.claim("old", Duration.ofMillis(200)).orElseThrow();
        assertThat(queue.claim("new", Duration.ofMinutes(1))).isEmpty();
        Thread.sleep(400);
        JobQueue.Job again = queue.claim("new", Duration.ofMinutes(1)).orElseThrow();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.attempts()).isEqualTo(2);
        assertThat(queue.complete(first.id(), "old")).isFalse();
        assertThat(queue.complete(again.id(), "new")).isTrue();
    }

    @Test
    void failureRetriesLaterThenGoesDead() {
        jdbc.sql("insert into jobs (type, payload, max_attempts) values ('t', '{}', 2)").update();
        JobQueue.Job j1 = queue.claim("w", Duration.ofMinutes(1)).orElseThrow();
        queue.fail(j1, "w", "boom", false);
        assertThat(status(j1.id())).isEqualTo("ready");
        // backoff puts it in the future, so it is not claimable right away
        assertThat(queue.claim("w", Duration.ofMinutes(1))).isEmpty();
        jdbc.sql("update jobs set run_at = now() where id = ?").param(j1.id()).update();
        JobQueue.Job j2 = queue.claim("w", Duration.ofMinutes(1)).orElseThrow();
        queue.fail(j2, "w", "boom", false);
        assertThat(status(j1.id())).isEqualTo("dead");
    }

    @Test
    void permanentFailureIsDeadAtOnce() {
        queue.enqueue("t", "{}", "perm");
        JobQueue.Job j = queue.claim("w", Duration.ofMinutes(1)).orElseThrow();
        queue.fail(j, "w", "404", true);
        assertThat(status(j.id())).isEqualTo("dead");
    }

    @Test
    void backoffGrowsAndIsCapped() {
        assertThat(JobQueue.backoff(1)).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(36));
        assertThat(JobQueue.backoff(3)).isBetween(Duration.ofSeconds(120), Duration.ofSeconds(144));
        assertThat(JobQueue.backoff(30)).isLessThanOrEqualTo(Duration.ofHours(6).plusMinutes(72));
    }
}

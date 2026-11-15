package io.github.itsmeck24.fineprint.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.itsmeck24.fineprint.DbTest;
import io.github.itsmeck24.fineprint.FakeMail;
import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.queue.Changes;
import io.github.itsmeck24.fineprint.store.NotificationStore;
import io.github.itsmeck24.fineprint.store.PostingStore;
import io.github.itsmeck24.fineprint.store.UserStore;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "fineprint.mail.lease=200ms")
class OutboxTest extends DbTest {

    @Autowired
    NotificationStore store;
    @Autowired
    Changes changes;
    @Autowired
    PostingStore postings;
    @Autowired
    UserStore users;
    @Autowired
    FinePrintProperties props;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();
    FakeMail mail;
    OutboxSender sender;
    static int seq;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from notifications").update();
    }

    @AfterEach
    void tearDown() {
        if (mail != null) {
            mail.stop();
        }
    }

    OutboxSender sender(boolean idempotent) {
        mail = new FakeMail(idempotent);
        return new OutboxSender(store, new ResendMailSender(mail.base(), "test-key", "a@b.test", json), props);
    }

    // a posting saved by two users that just closed
    long closedEvent() {
        seq++;
        long board = postings.boardId("greenhouse", "outbox-" + seq);
        long posting = postings.ensurePosting(board, "1");
        for (String who : List.of("u1", "u2")) {
            long u = users.create(who + "-" + seq + "@example.test", "x", YearMonth.of(2027, 5), "master").orElseThrow();
            users.save(u, posting);
        }
        return tx.execute(s -> changes.closed(posting, "t" + seq)).orElseThrow();
    }

    int count(String status) {
        return jdbc.sql("select count(*) from notifications where status = ?").param(status).query(Integer.class).single();
    }

    @Test
    void oneRowPerSaverAndNeverTwice() {
        long event = closedEvent();
        assertThat(count("pending")).isEqualTo(2);
        assertThat(store.fanOut(event, jdbc.sql("select posting_id from change_events where id = ?").param(event)
                .query(Long.class).single(), "s", "b")).isZero();
        assertThat(count("pending")).isEqualTo(2);
    }

    @Test
    void sendsEachOnce() throws Exception {
        OutboxSender s = sender(true);
        closedEvent();
        while (s.sendOne()) {
        }
        assertThat(count("sent")).isEqualTo(2);
        assertThat(mail.delivered()).hasSize(2);
    }

    // the worker dies after the provider accepted the message but before it wrote "sent".
    // the retry carries the same key, so a provider with idempotency keys delivers once. smtp-like would not.
    @Test
    void crashBetweenSendAndRecordIsDeduplicatedByTheProviderKey() throws Exception {
        for (boolean idempotent : List.of(true, false)) {
            jdbc.sql("delete from notifications").update();
            OutboxSender s = sender(idempotent);
            closedEvent();
            NotificationStore.Claimed c = store.claim("dead-worker", Duration.ofMillis(100)).orElseThrow();
            new ResendMailSender(mail.base(), "k", "a@b.test", json)
                    .send(new MailSender.Message(c.email(), c.subject(), c.body(), "fineprint-notification-" + c.id()));
            Thread.sleep(300);
            while (s.sendOne()) {
            }
            assertThat(count("sent")).isEqualTo(2);
            assertThat(mail.requests()).hasSize(3);
            assertThat(mail.delivered()).hasSize(idempotent ? 2 : 3);
            mail.stop();
        }
    }

    @Test
    void interruptedAttemptOlderThanTheKeyWindowIsNotRetriedBlindly() throws Exception {
        OutboxSender s = sender(true);
        closedEvent();
        NotificationStore.Claimed c = store.claim("dead-worker", Duration.ofMillis(100)).orElseThrow();
        jdbc.sql("update notifications set last_attempt_at = now() - interval '25 hours', lease_until = now() - interval '1 hour' where id = ?")
                .param(c.id()).update();
        while (s.sendOne()) {
        }
        assertThat(count("delivery_unknown")).isEqualTo(1);
        assertThat(count("sent")).isEqualTo(1);
        assertThat(mail.requests()).hasSize(1);
    }

    @Test
    void cleanFailuresRetryEvenAfterADay() throws Exception {
        OutboxSender s = sender(true);
        closedEvent();
        mail.failNext(2, 503);
        s.sendOne();
        s.sendOne();
        assertThat(count("pending")).isEqualTo(2);
        jdbc.sql("update notifications set first_attempt_at = now() - interval '2 days', next_attempt_at = now()").update();
        while (s.sendOne()) {
        }
        assertThat(count("sent")).isEqualTo(2);
        assertThat(count("delivery_unknown")).isZero();
    }

    @Test
    void providerRejectionIsFinal() throws Exception {
        OutboxSender s = sender(true);
        closedEvent();
        mail.failNext(1, 422);
        s.sendOne();
        assertThat(count("failed")).isEqualTo(1);
    }
}

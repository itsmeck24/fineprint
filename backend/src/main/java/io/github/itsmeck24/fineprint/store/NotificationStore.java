package io.github.itsmeck24.fineprint.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationStore {

    // interruptedAttemptAt is set when the previous attempt was cut off mid-send (its lease ran out),
    // which is the only case where an earlier send may have gone through without being recorded
    public record Claimed(long id, String email, String subject, String body, int attempts,
                          Instant interruptedAttemptAt, Instant now) {
    }

    public record Row(long id, String kind, String subject, String status, Instant createdAt, Instant sentAt) {
    }

    private final JdbcClient jdbc;

    public NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // one row per user who saved the posting. runs inside the caller's transaction.
    public int fanOut(long changeEventId, long postingId, String subject, String body) {
        return jdbc.sql("""
                        insert into notifications (user_id, kind, change_event_id, dedup_key, subject, body)
                        select s.user_id, 'change', ?, 'change:' || ? || ':' || s.user_id, ?, ?
                        from saved_postings s where s.posting_id = ?
                        on conflict (dedup_key) do nothing""")
                .params(changeEventId, changeEventId, subject, body, postingId)
                .update();
    }

    public boolean insertDigest(long userId, String dedupKey, String subject, String body) {
        return jdbc.sql("""
                        insert into notifications (user_id, kind, dedup_key, subject, body)
                        values (?, 'digest', ?, ?, ?) on conflict (dedup_key) do nothing""")
                .params(userId, dedupKey, subject, body).update() == 1;
    }

    // same claim pattern as the job queue: skip locked plus a lease that another sender can take over
    public Optional<Claimed> claim(String sender, Duration lease) {
        return jdbc.sql("""
                        with picked as (
                            select id, status as old_status, last_attempt_at as old_attempt_at from notifications
                            where (status = 'pending' and next_attempt_at <= now())
                               or (status = 'sending' and lease_until < now())
                            order by id
                            limit 1
                            for update skip locked)
                        update notifications n set status = 'sending', locked_by = ?, attempts = attempts + 1,
                            lease_until = now() + make_interval(secs => ?),
                            first_attempt_at = coalesce(first_attempt_at, now()),
                            last_attempt_at = now()
                        from picked, users u
                        where n.id = picked.id and u.id = n.user_id
                        returning n.id, u.email, n.subject, n.body, n.attempts,
                            case when picked.old_status = 'sending' then picked.old_attempt_at end, now()""")
                .params(sender, lease.toMillis() / 1000.0)
                .query((rs, n) -> new Claimed(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7).toInstant()))
                .optional();
    }

    public boolean markSent(long id, String sender, String providerMessageId) {
        return jdbc.sql("""
                        update notifications set status = 'sent', sent_at = now(), provider_message_id = ?,
                            lease_until = null
                        where id = ? and locked_by = ? and status = 'sending'""")
                .params(providerMessageId, id, sender).update() == 1;
    }

    public void retryLater(long id, String sender, String error, Duration wait) {
        jdbc.sql("""
                        update notifications set status = 'pending', last_error = ?, lease_until = null,
                            next_attempt_at = now() + make_interval(secs => ?)
                        where id = ? and locked_by = ? and status = 'sending'""")
                .params(error, wait.toMillis() / 1000.0, id, sender).update();
    }

    public void finish(long id, String sender, String status, String error) {
        jdbc.sql("""
                        update notifications set status = ?, last_error = ?, lease_until = null
                        where id = ? and locked_by = ? and status = 'sending'""")
                .params(status, error, id, sender).update();
    }

    public List<Row> forUser(long userId, int limit) {
        return jdbc.sql("""
                        select id, kind, subject, status, created_at, sent_at from notifications
                        where user_id = ? order by id desc limit ?""")
                .params(userId, limit)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()))
                .list();
    }
}

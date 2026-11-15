package io.github.itsmeck24.fineprint.queue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobQueue {

    public record Job(long id, String type, String payload, int attempts, int maxAttempts) {
    }

    private final JdbcClient jdbc;

    public JobQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // returns false when the same dedup key is already waiting or running
    public boolean enqueue(String type, String payloadJson, String dedupKey) {
        return enqueue(type, payloadJson, dedupKey, Duration.ZERO);
    }

    public boolean enqueue(String type, String payloadJson, String dedupKey, Duration delay) {
        int n = jdbc.sql("""
                        insert into jobs (type, payload, dedup_key, run_at)
                        values (?, ?::jsonb, ?, now() + make_interval(secs => ?))
                        on conflict (dedup_key) where status in ('ready', 'running') do nothing""")
                .params(type, payloadJson, dedupKey, delay.toMillis() / 1000.0)
                .update();
        return n == 1;
    }

    // takes the oldest due job, or a running job whose worker stopped renewing its lease.
    // skip locked means two workers never wait on or take the same row.
    public Optional<Job> claim(String workerId, Duration lease) {
        return jdbc.sql("""
                        update jobs set status = 'running', locked_by = ?, attempts = attempts + 1,
                            lease_until = now() + make_interval(secs => ?)
                        where id = (
                            select id from jobs
                            where (status = 'ready' and run_at <= now())
                               or (status = 'running' and lease_until < now())
                            order by run_at
                            limit 1
                            for update skip locked)
                        returning id, type, payload::text, attempts, max_attempts""")
                .params(workerId, lease.toMillis() / 1000.0)
                .query((rs, n) -> new Job(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)))
                .optional();
    }

    // only the worker that still holds the lease may finish the job
    public boolean complete(long jobId, String workerId) {
        return jdbc.sql("""
                        update jobs set status = 'done', finished_at = now(), lease_until = null
                        where id = ? and locked_by = ? and status = 'running'""")
                .params(jobId, workerId).update() == 1;
    }

    public boolean fail(Job job, String workerId, String error, boolean permanent) {
        boolean dead = permanent || job.attempts() >= job.maxAttempts();
        return jdbc.sql("""
                        update jobs set status = ?, last_error = ?, lease_until = null,
                            run_at = now() + make_interval(secs => ?),
                            finished_at = case when ? then now() else null end
                        where id = ? and locked_by = ? and status = 'running'""")
                .params(dead ? "dead" : "ready", truncate(error), backoff(job.attempts()).toMillis() / 1000.0, dead,
                        job.id(), workerId)
                .update() == 1;
    }

    // 30s, 1m, 2m, 4m ... capped at 6h, with up to 20% jitter so retries do not line up
    static Duration backoff(int attempts) {
        long base = 30L << Math.min(Math.max(attempts - 1, 0), 10);
        long capped = Math.min(base, Duration.ofHours(6).toSeconds());
        long jitter = (long) (capped * 0.2 * ThreadLocalRandom.current().nextDouble());
        return Duration.ofSeconds(capped + jitter);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}

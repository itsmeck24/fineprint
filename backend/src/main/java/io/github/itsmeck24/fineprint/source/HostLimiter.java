package io.github.itsmeck24.fineprint.source;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// one request slot per host at a time, shared by every worker through the host_limits table.
// the update only succeeds when the slot is free, so two workers cannot both take it.
@Component
public class HostLimiter {

    private final JdbcClient jdbc;

    public HostLimiter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // blocks until the host is free, then returns the moment the slot was taken
    public Instant acquire(String host, Duration gap) throws InterruptedException {
        jdbc.sql("insert into host_limits (host) values (?) on conflict do nothing").param(host).update();
        double gapSeconds = gap.toNanos() / 1e9;
        while (true) {
            Optional<Timestamp> taken = jdbc.sql("""
                            update host_limits
                            set next_allowed_at = clock_timestamp() + make_interval(secs => ?)
                            where host = ? and next_allowed_at <= clock_timestamp()
                            returning clock_timestamp()""")
                    .params(gapSeconds, host)
                    .query(Timestamp.class).optional();
            if (taken.isPresent()) {
                return taken.get().toInstant();
            }
            Double waitSeconds = jdbc.sql("""
                            select greatest(extract(epoch from next_allowed_at - clock_timestamp()), 0)
                            from host_limits where host = ?""")
                    .param(host).query(Double.class).single();
            Thread.sleep(Math.max(1, (long) Math.ceil(waitSeconds * 1000)));
        }
    }
}

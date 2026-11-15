package io.github.itsmeck24.fineprint.mail;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.store.NotificationStore;

// delivers the outbox. at-least-once with deduplication:
// - crash before the provider call: the lease runs out, another sender sends it. no duplicate.
// - crash after the call but before marking sent: the retry uses the same idempotency key, and a provider
//   that supports keys (resend, for 24 hours) drops the repeat. smtp does not, so a duplicate is possible there.
// - if the cut-off attempt started longer ago than unknown-after, the provider no longer remembers the key,
//   so it is not retried blindly. it is marked delivery_unknown and shown.
// - clean failures (provider down, timeout before connecting) just retry with backoff.
@Component
@ConditionalOnProperty(name = "fineprint.worker.enabled", havingValue = "true")
public class OutboxSender implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxSender.class);

    private final NotificationStore store;
    private final MailSender mail;
    private final FinePrintProperties.Mail props;
    private final Duration idleSleep;
    private final String id = ManagementFactory.getRuntimeMXBean().getName() + "/outbox";
    private volatile boolean running;
    private Thread thread;

    public OutboxSender(NotificationStore store, MailSender mail, FinePrintProperties props) {
        this.store = store;
        this.mail = mail;
        this.props = props.mail();
        this.idleSleep = props.worker().idleSleep();
    }

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::loop, "outbox");
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                if (!sendOne()) {
                    Thread.sleep(idleSleep.toMillis());
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.warn("outbox loop error: {}", e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    // returns false when nothing was due
    public boolean sendOne() throws InterruptedException {
        Optional<NotificationStore.Claimed> c = store.claim(id, props.lease());
        if (c.isEmpty()) {
            return false;
        }
        NotificationStore.Claimed n = c.get();
        if (n.interruptedAttemptAt() != null && n.interruptedAttemptAt().plus(props.unknownAfter()).isBefore(n.now())) {
            store.finish(n.id(), id, "delivery_unknown", "first attempt too long ago to retry safely");
            log.warn("notification {} marked delivery_unknown", n.id());
            return true;
        }
        try {
            String providerId = mail.send(new MailSender.Message(n.email(), n.subject(), n.body(),
                    "fineprint-notification-" + n.id()));
            if (!store.markSent(n.id(), id, providerId)) {
                log.warn("notification {} sent after its lease was taken over", n.id());
            }
        } catch (MailSender.Rejected e) {
            store.finish(n.id(), id, "failed", e.getMessage());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            store.retryLater(n.id(), id, e.toString(), backoff(n.attempts()));
        }
        return true;
    }

    static Duration backoff(int attempts) {
        return Duration.ofSeconds(Math.min(15L << Math.min(attempts - 1, 8), 3600));
    }
}

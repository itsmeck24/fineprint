package io.github.itsmeck24.fineprint.queue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "fineprint.worker.enabled", havingValue = "true")
public class Worker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final JobQueue queue;
    private final FinePrintProperties.Worker props;
    private final JsonMapper json;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private final String id = ManagementFactory.getRuntimeMXBean().getName();
    private volatile boolean running;

    public Worker(JobQueue queue, FinePrintProperties props, JsonMapper json, List<JobHandler> handlers) {
        this.queue = queue;
        this.props = props.worker();
        this.json = json;
        for (JobHandler h : handlers) {
            this.handlers.put(h.type(), h);
        }
    }

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < props.threads(); i++) {
            Thread t = new Thread(this::loop, "worker-" + i);
            threads.add(t);
            t.start();
        }
        log.info("worker {} started with {} threads", id, props.threads());
    }

    @Override
    public void stop() {
        running = false;
        for (Thread t : threads) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        String me = id + "/" + Thread.currentThread().getName();
        while (running) {
            try {
                Optional<JobQueue.Job> job = queue.claim(me, props.lease());
                if (job.isEmpty()) {
                    Thread.sleep(props.idleSleep().toMillis());
                    continue;
                }
                runOne(job.get(), me);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                // database hiccup or similar. back off a little and keep going
                log.warn("worker loop error: {}", e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    void runOne(JobQueue.Job job, String me) throws InterruptedException {
        JobHandler h = handlers.get(job.type());
        if (h == null) {
            queue.fail(job, me, "no handler for " + job.type(), true);
            return;
        }
        try {
            h.run(json.readTree(job.payload()));
            if (!queue.complete(job.id(), me)) {
                log.warn("job {} finished after its lease was taken over", job.id());
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (JobHandler.Permanent e) {
            log.info("job {} {} dead: {}", job.id(), job.type(), e.getMessage());
            queue.fail(job, me, e.getMessage(), true);
        } catch (Exception e) {
            log.info("job {} {} attempt {} failed: {}", job.id(), job.type(), job.attempts(), e.toString());
            queue.fail(job, me, e.toString(), false);
        }
    }
}

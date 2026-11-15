package io.github.itsmeck24.fineprint.queue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

// runs queued jobs one by one on the test thread until nothing is due
public final class Drain {

    private Drain() {
    }

    public static int all(JobQueue queue, List<JobHandler> handlers, JsonMapper json) throws Exception {
        int n = 0;
        Optional<JobQueue.Job> j;
        while ((j = queue.claim("test", Duration.ofMinutes(5))).isPresent()) {
            JobQueue.Job job = j.get();
            JobHandler h = handlers.stream().filter(x -> x.type().equals(job.type())).findFirst().orElseThrow();
            try {
                h.run(json.readTree(job.payload()));
                queue.complete(job.id(), "test");
            } catch (JobHandler.Permanent e) {
                queue.fail(job, "test", e.getMessage(), true);
            } catch (Exception e) {
                queue.fail(job, "test", e.toString(), false);
            }
            n++;
        }
        return n;
    }
}

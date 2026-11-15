package io.github.itsmeck24.fineprint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.itsmeck24.fineprint.queue.Jobs;
import io.github.itsmeck24.fineprint.store.PostingStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// how many postings per second one worker process gets through with 1, 2, 4 and 8 threads, against a fake board
// that answers every request after a fixed delay. each posting is a fetch job plus an extract job.
//   ./mvnw test -Pcrash -Dtest=ThroughputBenchTest
@Tag("crash")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ThroughputBenchTest extends DbTest {

    static final int POSTINGS = Integer.getInteger("bench.postings", 400);
    static final int DELAY_MS = Integer.getInteger("bench.delay-ms", 100);
    static final int REPEATS = Integer.getInteger("bench.repeats", 5);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PostingStore postings;
    @Autowired
    Jobs jobs;

    final FakeBoards boards = new FakeBoards();

    Process start(int threads) throws Exception {
        Path logs = Path.of("target", "bench-logs");
        Files.createDirectories(logs);
        ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "io.github.itsmeck24.fineprint.FinePrintApplication");
        Map<String, String> env = pb.environment();
        env.put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        env.put("SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "20");
        env.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "none");
        env.put("SPRING_FLYWAY_ENABLED", "false");
        env.put("FINEPRINT_WORKER_ENABLED", "true");
        env.put("FINEPRINT_WORKER_THREADS", Integer.toString(threads));
        env.put("FINEPRINT_WORKER_IDLE_SLEEP", "20ms");
        env.put("FINEPRINT_COLLECTOR_ENABLED", "false");
        env.put("FINEPRINT_COLLECTOR_MIN_GAP", "0s");
        env.put("FINEPRINT_SOURCES_GREENHOUSE_BASE", boards.base() + "/v1/boards/");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("bench-" + threads + ".log").toFile()));
        return pb.start();
    }

    int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    @Test
    void throughputByThreadCount() throws Exception {
        boards.maxDelay(0);
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode r = json.createObjectNode();
        ArrayNode rows = r.putArray("results");
        int run = 0;
        for (int threads : List.of(1, 2, 4, 8)) {
            List<Double> rates = new ArrayList<>();
            for (int rep = 0; rep < REPEATS; rep++) {
                run++;
                String token = "bench" + run;
                long board = postings.boardId("greenhouse", token);
                for (int i = 0; i < POSTINGS; i++) {
                    boards.put("/v1/boards/" + token + "/jobs/" + i + "?questions=true", 200, FakeBoards.greenhouseDetail(i,
                            "Software Engineer Intern", "<p>You will graduate in fall 2027 or spring 2028.</p>", "[]"));
                }
                boards.fixedDelay(DELAY_MS);
                jdbc.sql("delete from jobs").update();
                Process worker = start(threads);
                // wait for the worker to be up before the clock starts
                Thread.sleep(4000);
                Instant t0 = Instant.now();
                for (int i = 0; i < POSTINGS; i++) {
                    jobs.fetchPosting(postings.ensurePosting(board, Integer.toString(i)));
                }
                while (count("""
                        select count(*) from posting_versions v join postings p on p.id = v.posting_id
                        where p.board_id = ? and v.extracted_at is not null""", board) < POSTINGS) {
                    Thread.sleep(50);
                }
                double seconds = Duration.between(t0, Instant.now()).toMillis() / 1000.0;
                worker.destroy();
                worker.waitFor();
                rates.add(POSTINGS / seconds);
            }
            double mean = rates.stream().mapToDouble(d -> d).average().orElse(0);
            double sd = Math.sqrt(rates.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum() / (rates.size() - 1));
            ObjectNode row = rows.addObject();
            row.put("threads", threads);
            row.put("postings_per_second_mean", Math.round(mean * 10) / 10.0);
            row.put("postings_per_second_sd", Math.round(sd * 10) / 10.0);
            row.set("runs", json.valueToTree(rates.stream().map(x -> Math.round(x * 10) / 10.0).toList()));
            System.out.println(threads + " threads: " + row);
        }
        r.put("command", "./mvnw test -Pcrash -Dtest=ThroughputBenchTest -Dbench.postings=" + POSTINGS
                + " -Dbench.delay-ms=" + DELAY_MS + " -Dbench.repeats=" + REPEATS);
        r.put("commit", CrashHarnessTest.git("rev-parse", "--short", "HEAD"));
        r.put("uncommitted_changes", !CrashHarnessTest.git("status", "--porcelain").isBlank());
        r.put("machine", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors() + " cpus");
        r.put("java", System.getProperty("java.version"));
        r.put("postgres", postgres.getDockerImageName() + " in docker");
        r.put("workload", POSTINGS + " postings, each one fetch job and one extract job, fake board answers after "
                + DELAY_MS + " ms, one worker process, host rate limit off");
        Path out = Path.of("..", "results");
        Files.createDirectories(out);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Files.writeString(out.resolve("throughput-" + stamp + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(r) + "\n", StandardCharsets.UTF_8);
    }
}

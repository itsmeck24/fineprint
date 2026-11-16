package io.github.itsmeck24.fineprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.itsmeck24.fineprint.store.PostingStore;
import io.github.itsmeck24.fineprint.store.UserStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// kill -9 test. two real worker processes run the queue and the outbox against a fake job board and a fake
// resend. the board keeps changing every posting's graduation window. a killer stops a random worker at a random
// moment and starts a new one. at the end every count comes from the database and the fake provider.
//   ./mvnw test -Pcrash -Dcrash.kills=100
@Tag("crash")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CrashHarnessTest extends DbTest {

    static final int POSTINGS = Integer.getInteger("crash.postings", 20);
    static final int TARGET_KILLS = Integer.getInteger("crash.kills", 100);
    static final int WORKERS = Integer.getInteger("crash.workers", 2);
    static final long SEED = Long.getLong("crash.seed", 7);
    static final int BOARD_DELAY_MS = Integer.getInteger("crash.board-delay-ms", 300);
    static final int MAIL_DELAY_MS = Integer.getInteger("crash.mail-delay-ms", 300);
    static final String[] SEASONS = {"spring", "summer", "fall", "winter"};

    @Autowired
    JdbcClient jdbc;
    @Autowired
    PostingStore postings;
    @Autowired
    UserStore users;

    final FakeBoards boards = new FakeBoards();
    final FakeMail mail = new FakeMail(true);
    final Random random = new Random(SEED);
    final List<Long> postingIds = new ArrayList<>();
    final Path logDir = Path.of("target", "crash-logs");

    record Worker(Process process, long pid) {
    }

    void publish(int generation) {
        StringBuilder list = new StringBuilder("{\"jobs\":[");
        String updated = Instant.parse("2026-11-01T00:00:00Z").plusSeconds(generation * 60L).toString()
                .replace("Z", "+00:00");
        for (int i = 0; i < POSTINGS; i++) {
            long id = 5000 + i;
            list.append(i == 0 ? "" : ",").append("{\"id\":").append(id)
                    .append(",\"title\":\"Software Engineer Intern\",\"updated_at\":\"").append(updated).append("\"}");
            // the year wraps within 2027 to 2076, the date rules only read years 2000 to 2099
            String sentence = "You will graduate in " + SEASONS[generation % 4] + " " + (2027 + (generation / 4) % 50) + ".";
            boards.put("/v1/boards/crash/jobs/" + id + "?questions=true", 200,
                    FakeBoards.greenhouseDetail(id, "Software Engineer Intern", "<p>" + sentence + "</p>", "[]")
                            .replace("2026-11-01T09:00:00-04:00", updated));
        }
        boards.put("/v1/boards/crash/jobs", 200, list.append("]}").toString());
    }

    Worker start(int n) throws IOException {
        Files.createDirectories(logDir);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "io.github.itsmeck24.fineprint.FinePrintApplication");
        Map<String, String> env = pb.environment();
        env.put("SPRING_DATASOURCE_URL", postgres.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", postgres.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", postgres.getPassword());
        env.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "none");
        env.put("SPRING_FLYWAY_ENABLED", "false");
        env.put("FINEPRINT_WORKER_ENABLED", "true");
        env.put("FINEPRINT_WORKER_THREADS", "4");
        env.put("FINEPRINT_WORKER_LEASE", "3s");
        env.put("FINEPRINT_WORKER_IDLE_SLEEP", "50ms");
        env.put("FINEPRINT_COLLECTOR_ENABLED", "true");
        env.put("FINEPRINT_COLLECTOR_POLL_EVERY", "PT1S");
        env.put("FINEPRINT_COLLECTOR_FIRST_POLL_AFTER", "PT0S");
        env.put("FINEPRINT_COLLECTOR_MIN_GAP", "0s");
        env.put("FINEPRINT_COLLECTOR_BOARDS_0", "greenhouse:crash");
        env.put("FINEPRINT_SOURCES_GREENHOUSE_BASE", boards.base() + "/v1/boards/");
        env.put("FINEPRINT_MAIL_PROVIDER", "resend");
        env.put("FINEPRINT_MAIL_RESEND_BASE", mail.base());
        env.put("FINEPRINT_MAIL_LEASE", "3s");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("worker-" + n + ".log").toFile()));
        Process p = pb.start();
        return new Worker(p, p.pid());
    }

    int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    int inFlight(long pid) {
        return count("select count(*) from jobs where status = 'running' and locked_by like ?", pid + "@%")
                + count("select count(*) from notifications where status = 'sending' and locked_by like ?", pid + "@%");
    }

    int events() {
        return count("select count(*) from change_events where kind = 'rules_changed' and posting_id = any(?)",
                (Object) postingIds.toArray(new Long[0]));
    }

    @Test
    void killWorkersRepeatedlyAndCheckNothingIsLostOrDoubled() throws Exception {
        long board = postings.boardId("greenhouse", "crash");
        long u1 = users.create("crash1@example.test", "x", YearMonth.of(2027, 5), "master").orElseThrow();
        long u2 = users.create("crash2@example.test", "x", YearMonth.of(2027, 5), "master").orElseThrow();
        for (int i = 0; i < POSTINGS; i++) {
            long id = postings.ensurePosting(board, Long.toString(5000 + i));
            postingIds.add(id);
            users.save(u1, id);
            users.save(u2, id);
        }

        boards.maxDelay(BOARD_DELAY_MS);
        mail.maxDelay(MAIL_DELAY_MS);
        int generation = 0;
        publish(generation);
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            workers.add(start(i));
        }
        Instant began = Instant.now();
        int kills = 0;
        int killsInFlight = 0;
        int workerSeq = WORKERS;
        Instant nextKill = Instant.now().plusMillis(1000 + random.nextInt(3000));

        // first wait for the initial versions, then each generation must be fully recorded before the next
        while (kills < TARGET_KILLS) {
            if (Instant.now().isAfter(nextKill)) {
                int which = random.nextInt(workers.size());
                Worker w = workers.get(which);
                int busy = inFlight(w.pid());
                w.process().destroyForcibly().waitFor();
                kills++;
                killsInFlight += busy > 0 ? 1 : 0;
                workers.set(which, start(workerSeq++));
                nextKill = Instant.now().plusMillis(1000 + random.nextInt(3000));
            }
            int expected = POSTINGS * generation;
            boolean baseline = count("select count(*) from posting_versions where posting_id = any(?) and extracted_at is not null",
                    (Object) postingIds.toArray(new Long[0])) >= POSTINGS * (generation + 1);
            if (baseline && events() >= expected) {
                generation++;
                publish(generation);
            }
            Thread.sleep(100);
        }

        // no more kills: let everything finish, including jobs waiting out a retry backoff
        Instant settleUntil = Instant.now().plus(Duration.ofMinutes(Integer.getInteger("crash.settle-minutes", 30)));
        int finalGeneration = generation;
        Instant settleStarted = Instant.now();
        while (Instant.now().isBefore(settleUntil)) {
            boolean versionsDone = count("select count(*) from posting_versions where posting_id = any(?) and extracted_at is not null",
                    (Object) postingIds.toArray(new Long[0])) >= POSTINGS * (finalGeneration + 1);
            boolean eventsDone = events() >= POSTINGS * finalGeneration;
            boolean mailDone = count("select count(*) from notifications where status in ('pending', 'sending')") == 0;
            boolean jobsDone = count("select count(*) from jobs where status in ('ready', 'running') and type <> 'poll_board'") == 0;
            if (versionsDone && eventsDone && mailDone || jobsDone && mailDone && Instant.now().isAfter(settleStarted.plusSeconds(30))) {
                break;
            }
            Thread.sleep(200);
        }
        Duration took = Duration.between(began, Instant.now());
        for (Worker w : workers) {
            w.process().destroyForcibly().waitFor();
        }

        Long[] ids = postingIds.toArray(new Long[0]);
        int expectedEvents = POSTINGS * finalGeneration;
        int recordedEvents = events();
        int eventsWithDuplicatePair = count("""
                select count(*) from (select posting_id, from_version_id, to_version_id from change_events
                                      where posting_id = any(?) group by 1, 2, 3 having count(*) > 1) d""", (Object) ids);
        int expectedNotifications = recordedEvents * 2;
        int notificationRows = count("""
                select count(*) from notifications n join change_events e on e.id = n.change_event_id
                where e.posting_id = any(?)""", (Object) ids);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : List.of("sent", "pending", "sending", "delivery_unknown", "failed")) {
            byStatus.put(s, count("select count(*) from notifications where status = ?", s));
        }
        Map<String, Integer> requestsPerKey = new HashMap<>();
        for (String k : mail.requests()) {
            requestsPerKey.merge(k, 1, Integer::sum);
        }
        Map<String, Integer> deliveredPerKey = new HashMap<>();
        for (String k : mail.delivered()) {
            deliveredPerKey.merge(k, 1, Integer::sum);
        }
        int keysRequestedMoreThanOnce = (int) requestsPerKey.values().stream().filter(v -> v > 1).count();
        int duplicateDeliveries = deliveredPerKey.values().stream().mapToInt(v -> v - 1).sum();
        int sentNotDelivered = count("select count(*) from notifications where status = 'sent'") - deliveredPerKey.size();
        int jobsRetried = count("select count(*) from jobs where attempts > 1");
        int deadJobs = count("select count(*) from jobs where status = 'dead'");

        JsonMapper json = JsonMapper.builder().build();
        ObjectNode r = json.createObjectNode();
        r.put("command", "./mvnw test -Pcrash -Dcrash.kills=" + TARGET_KILLS + " -Dcrash.postings=" + POSTINGS
                + " -Dcrash.workers=" + WORKERS + " -Dcrash.seed=" + SEED + " -Dcrash.board-delay-ms=" + BOARD_DELAY_MS
                + " -Dcrash.mail-delay-ms=" + MAIL_DELAY_MS);
        r.put("commit", git("rev-parse", "--short", "HEAD"));
        r.put("uncommitted_changes", !git("status", "--porcelain").isBlank());
        r.put("started_utc", began.toString());
        r.put("duration_seconds", took.toSeconds());
        r.put("machine", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch") + ", " + Runtime.getRuntime().availableProcessors() + " cpus");
        r.put("java", System.getProperty("java.version"));
        r.put("postgres", postgres.getDockerImageName());
        r.put("workers_at_once", WORKERS);
        r.put("worker_threads_each", 4);
        r.put("lease_seconds", 3);
        r.put("fake_board_delay_ms_max", BOARD_DELAY_MS);
        r.put("fake_mail_delay_ms_max", MAIL_DELAY_MS);
        r.put("postings", POSTINGS);
        r.put("users_per_posting", 2);
        r.put("generations_published", finalGeneration);
        r.put("kills", kills);
        r.put("kills_with_work_in_flight", killsInFlight);
        r.put("expected_change_events", expectedEvents);
        r.put("recorded_change_events", recordedEvents);
        r.put("lost_change_events", expectedEvents - Math.min(recordedEvents, expectedEvents));
        r.put("duplicate_change_events", eventsWithDuplicatePair + Math.max(0, recordedEvents - expectedEvents));
        r.put("expected_notifications", expectedNotifications);
        r.put("notification_rows", notificationRows);
        r.set("notification_status", json.valueToTree(byStatus));
        r.put("provider_requests", mail.requests().size());
        r.put("provider_keys_requested_more_than_once", keysRequestedMoreThanOnce);
        r.put("provider_deliveries", mail.delivered().size());
        r.put("duplicate_deliveries", duplicateDeliveries);
        r.put("sent_but_not_delivered", sentNotDelivered);
        r.put("jobs_retried_after_lease_or_error", jobsRetried);
        r.put("settle_seconds", Duration.between(settleStarted, Instant.now()).toSeconds());
        // where any shortfall comes from: work still queued, a version never fetched, or a version without its event
        ObjectNode diag = r.putObject("diagnostics");
        diag.set("pending_jobs", json.valueToTree(jdbc.sql("""
                select type || ':' || status || ':attempts=' || attempts as k from jobs
                where status in ('ready', 'running') and type <> 'poll_board'""").query(String.class).list()));
        diag.put("postings_missing_versions", count("""
                select count(*) from (select p.id from postings p left join posting_versions v on v.posting_id = p.id
                where p.id = any(?) group by p.id having count(v.id) < ?) x""", ids, finalGeneration + 1));
        diag.put("versions_not_extracted", count("""
                select count(*) from posting_versions where posting_id = any(?) and extracted_at is null""", (Object) ids));
        diag.put("adjacent_version_pairs_without_event", count("""
                select count(*) from posting_versions v
                join lateral (select id from posting_versions n where n.posting_id = v.posting_id and n.id > v.id
                              order by n.id limit 1) nxt on true
                where v.posting_id = any(?)
                  and not exists (select 1 from change_events e where e.from_version_id = v.id and e.to_version_id = nxt.id)""",
                (Object) ids));
        r.put("dead_jobs", deadJobs);

        Path out = Path.of("..", "results");
        Files.createDirectories(out);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(began);
        Files.writeString(out.resolve("crash-" + TARGET_KILLS + "-kills-" + stamp + ".json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(r) + "\n", StandardCharsets.UTF_8);
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(r));

        assertThat(recordedEvents).isEqualTo(expectedEvents);
        assertThat(eventsWithDuplicatePair).isZero();
        assertThat(notificationRows).isEqualTo(expectedNotifications);
        assertThat(byStatus.get("pending") + byStatus.get("sending")).isZero();
    }

    static String git(String... args) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git"));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).directory(new File("..")).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "unknown";
        }
    }
}

package io.github.itsmeck24.fineprint.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.sun.net.httpserver.HttpServer;

import io.github.itsmeck24.fineprint.DbTest;

// a local http server stands in for the job board, nothing leaves the machine
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "fineprint.collector.min-gap=300ms")
class HttpFetcherTest extends DbTest {

    @Autowired
    HttpFetcher http;
    @Autowired
    JdbcClient jdbc;

    HttpServer server;
    String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(ex.getRequestURI().getPath().equals("/missing") ? 404 : 200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private List<Timestamp> starts() {
        return jdbc.sql("select started_at from fetch_log where url like ? order by started_at")
                .param(base + "%").query(Timestamp.class).list();
    }

    @Test
    void requestsToOneHostAreSpacedByAtLeastTheMinimumGap() throws Exception {
        for (int i = 0; i < 4; i++) {
            http.get(base + "/jobs/" + i);
        }
        assertGaps(starts(), 4);
    }

    // the limit lives in the database, so threads (or separate worker processes) share it
    @Test
    void threadsShareOneLimitPerHost() throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            int n = t;
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    http.get(base + "/thread/" + n);
                    http.get(base + "/thread/" + n + "/again");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertGaps(starts(), 6);
    }

    @Test
    void non200IsReturnedAndLogged() throws Exception {
        assertThat(http.get(base + "/missing").status()).isEqualTo(404);
        assertThat(jdbc.sql("select status from fetch_log where url = ?").param(base + "/missing")
                .query(Integer.class).single()).isEqualTo(404);
    }

    private static void assertGaps(List<Timestamp> starts, int expected) {
        assertThat(starts).hasSize(expected);
        for (int i = 1; i < starts.size(); i++) {
            Duration gap = Duration.between(starts.get(i - 1).toInstant(), starts.get(i).toInstant());
            assertThat(gap).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        }
    }
}

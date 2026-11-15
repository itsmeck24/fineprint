package io.github.itsmeck24.fineprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sun.net.httpserver.HttpServer;

// a local http server stands in for the job board, nothing leaves the machine
@SpringBootTest(properties = {"fineprint.collector.enabled=false", "fineprint.collector.min-gap=300ms"})
@Testcontainers
class HttpFetcherTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

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
        jdbc.sql("delete from fetch_log").update();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void requestsToOneHostAreSpacedByAtLeastTheMinimumGap() throws Exception {
        for (int i = 0; i < 4; i++) {
            http.get(base + "/jobs/" + i);
        }
        List<Timestamp> starts = jdbc.sql("select started_at from fetch_log order by started_at")
                .query(Timestamp.class).list();
        assertThat(starts).hasSize(4);
        for (int i = 1; i < starts.size(); i++) {
            Duration gap = Duration.between(starts.get(i - 1).toInstant(), starts.get(i).toInstant());
            assertThat(gap).isGreaterThanOrEqualTo(Duration.ofMillis(300));
        }
    }

    @Test
    void non200IsReturnedAndLogged() throws Exception {
        assertThat(http.get(base + "/missing").status()).isEqualTo(404);
        assertThat(jdbc.sql("select status from fetch_log").query(Integer.class).single()).isEqualTo(404);
    }
}

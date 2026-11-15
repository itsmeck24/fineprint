package io.github.itsmeck24.fineprint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class HttpFetcher {

    public record Response(int status, String body) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final CollectorProperties props;
    private final JdbcClient jdbc;
    // in-memory per-host gap. fine for one collector process, moves into the db when there are several workers
    private final Map<String, Instant> lastCall = new HashMap<>();

    public HttpFetcher(CollectorProperties props, JdbcClient jdbc) {
        this.props = props;
        this.jdbc = jdbc;
    }

    public Response get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", props.userAgent())
                .header("Accept", "application/json")
                .GET()
                .build();
        Instant start = waitForHost(req.uri().getHost());
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            log(url, res.statusCode(), null, start);
            return new Response(res.statusCode(), res.body());
        } catch (IOException | RuntimeException e) {
            log(url, null, e.toString(), start);
            throw e;
        }
    }

    // returns the moment the request is allowed to go, which is also what gets logged as started_at
    private synchronized Instant waitForHost(String host) throws InterruptedException {
        Instant prev = lastCall.get(host);
        if (prev != null) {
            Instant allowed = prev.plus(props.minGap());
            Instant now;
            while ((now = Instant.now()).isBefore(allowed)) {
                Thread.sleep(Duration.between(now, allowed));
            }
        }
        Instant start = Instant.now();
        lastCall.put(host, start);
        return start;
    }

    private void log(String url, Integer status, String error, Instant start) {
        jdbc.sql("insert into fetch_log (url, status, error, started_at, duration_ms) values (?, ?, ?, ?, ?)")
                .params(url, status, error, java.sql.Timestamp.from(start),
                        (int) Duration.between(start, Instant.now()).toMillis())
                .update();
    }
}

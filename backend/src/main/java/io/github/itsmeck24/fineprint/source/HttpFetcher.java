package io.github.itsmeck24.fineprint.source;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.FinePrintProperties;

@Component
public class HttpFetcher {

    public record Response(int status, String body) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final FinePrintProperties.Collector props;
    private final HostLimiter limiter;
    private final JdbcClient jdbc;

    public HttpFetcher(FinePrintProperties props, HostLimiter limiter, JdbcClient jdbc) {
        this.props = props.collector();
        this.limiter = limiter;
        this.jdbc = jdbc;
    }

    public Response get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", props.userAgent())
                .header("Accept", "application/json")
                .GET()
                .build();
        String host = req.uri().getHost() + (req.uri().getPort() > 0 ? ":" + req.uri().getPort() : "");
        Instant start = limiter.acquire(host, props.minGap());
        try {
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            log(url, res.statusCode(), null, start);
            return new Response(res.statusCode(), res.body());
        } catch (IOException | RuntimeException e) {
            log(url, null, e.toString(), start);
            throw e;
        }
    }

    private void log(String url, Integer status, String error, Instant start) {
        jdbc.sql("insert into fetch_log (url, status, error, started_at, duration_ms) values (?, ?, ?, ?, ?)")
                .params(url, status, error, Timestamp.from(start),
                        (int) Math.max(0, Duration.between(start, Instant.now()).toMillis()))
                .update();
    }
}

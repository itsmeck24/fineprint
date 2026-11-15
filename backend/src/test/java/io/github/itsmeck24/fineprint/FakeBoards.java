package io.github.itsmeck24.fineprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

// a tiny stand-in for the greenhouse and lever apis. tests put responses in by path.
public class FakeBoards {

    public record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public FakeBoards() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", ex -> {
            String key = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            Reply r = replies.getOrDefault(key, new Reply(404, "{}"));
            byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void put(String pathAndQuery, int status, String body) {
        replies.put(pathAndQuery, new Reply(status, body));
    }

    public int hits(String pathAndQuery) {
        AtomicInteger n = hits.get(pathAndQuery);
        return n == null ? 0 : n.get();
    }

    public static String greenhouseDetail(long id, String title, String contentHtml, String questionsJson) {
        String escaped = contentHtml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "\\\"");
        return """
                {"id": %d, "internal_job_id": %d, "title": "%s", "absolute_url": "https://example.test/jobs/%d",
                 "location": {"name": "Remote"}, "first_published": "2026-10-01T09:00:00-04:00",
                 "updated_at": "2026-11-01T09:00:00-04:00", "content": "%s", "questions": %s}"""
                .formatted(id, id + 1000, title, id, escaped, questionsJson);
    }
}

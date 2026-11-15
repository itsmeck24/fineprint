package io.github.itsmeck24.fineprint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

// stands in for resend's POST /emails. with idempotent=true a repeated Idempotency-Key gets the first answer
// back and is not delivered again, which is what resend documents. with false it behaves like plain smtp.
public class FakeMail {

    private final HttpServer server;
    private final boolean idempotent;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> delivered = new CopyOnWriteArrayList<>();
    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile int failNext;
    private volatile int failStatus = 503;
    private volatile int maxDelayMs;

    public FakeMail(boolean idempotent) {
        this.idempotent = idempotent;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/emails", ex -> {
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            ex.getRequestBody().readAllBytes();
            requests.add(key);
            int status;
            String body;
            if (failNext > 0) {
                failNext--;
                status = failStatus;
                body = "{}";
            } else if (idempotent && key != null && seen.containsKey(key)) {
                status = 200;
                body = "{\"id\":\"" + seen.get(key) + "\"}";
            } else {
                String id = "msg-" + counter.incrementAndGet();
                if (key != null) {
                    seen.put(key, id);
                }
                delivered.add(key);
                status = 200;
                body = "{\"id\":\"" + id + "\"}";
            }
            // the delivery is recorded before this pause, so a worker killed while waiting here
            // is exactly the "provider accepted it, we never wrote sent" case
            FakeBoards.sleepUpTo(maxDelayMs);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public void maxDelay(int ms) {
        this.maxDelayMs = ms;
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void failNext(int n, int status) {
        this.failStatus = status;
        this.failNext = n;
    }

    public List<String> requests() {
        return requests;
    }

    public List<String> delivered() {
        return delivered;
    }

    public void stop() {
        server.stop(0);
    }
}

package io.github.itsmeck24.fineprint.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// resend's email api. the Idempotency-Key header makes resend drop a repeat of the same key for 24 hours.
// https://resend.com/docs/dashboard/emails/idempotency-keys
public class ResendMailSender implements MailSender {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final String apiKey;
    private final String from;
    private final JsonMapper json;

    public ResendMailSender(String base, String apiKey, String from, JsonMapper json) {
        this.base = base;
        this.apiKey = apiKey;
        this.from = from;
        this.json = json;
    }

    @Override
    public String send(Message m) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("from", from);
        body.putArray("to").add(m.to());
        body.put("subject", m.subject());
        body.put("text", m.body());
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/emails"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", m.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 200 || res.statusCode() == 201) {
            return json.readTree(res.body()).path("id").asString("");
        }
        if (res.statusCode() >= 400 && res.statusCode() < 500 && res.statusCode() != 429 && res.statusCode() != 409) {
            throw new Rejected("resend " + res.statusCode() + ": " + res.body());
        }
        throw new IOException("resend " + res.statusCode());
    }
}

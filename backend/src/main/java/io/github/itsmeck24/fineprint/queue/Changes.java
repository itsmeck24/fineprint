package io.github.itsmeck24.fineprint.queue;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.RuleDiff;
import io.github.itsmeck24.fineprint.store.ChangeStore;
import io.github.itsmeck24.fineprint.store.NotificationStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// the one place change events are written. callers run it inside their own transaction,
// and the notifications for everyone who saved the posting go into the same transaction.
@Component
public class Changes {

    private final ChangeStore store;
    private final NotificationStore notifications;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String appUrl;

    public Changes(ChangeStore store, NotificationStore notifications, JdbcClient jdbc, JsonMapper json,
                   FinePrintProperties props) {
        this.store = store;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.json = json;
        this.appUrl = props.mail().appUrl();
    }

    public Optional<Long> rulesChanged(long postingId, long fromVersion, long toVersion, RuleDiff.Diff diff) {
        ObjectNode summary = json.createObjectNode();
        write(summary.putArray("added"), diff.added());
        write(summary.putArray("removed"), diff.removed());
        Optional<Long> id = store.record(postingId, "rules_changed", fromVersion, toVersion,
                json.writeValueAsString(summary), "rules:" + fromVersion + ":" + toVersion);
        id.ifPresent(e -> notify(e, postingId, "changed its requirements", describe(diff)));
        return id;
    }

    // closedSince identifies this closing, so the same close is never recorded twice
    public Optional<Long> closed(long postingId, String closedSince) {
        Optional<Long> id = store.record(postingId, "closed", null, null, "{}", "closed:" + postingId + ":" + closedSince);
        id.ifPresent(e -> notify(e, postingId, "looks closed",
                "The posting dropped off its job board and its page returned 404 on two checks an hour apart."));
        return id;
    }

    public Optional<Long> reopened(long postingId, String closedAt) {
        Optional<Long> id = store.record(postingId, "reopened", null, null, "{}", "reopened:" + postingId + ":" + closedAt);
        id.ifPresent(e -> notify(e, postingId, "is open again", "The posting is back on its job board."));
        return id;
    }

    private void notify(long eventId, long postingId, String what, String detail) {
        record P(String title, String url) {
        }
        P p = jdbc.sql("select title, url from postings where id = ?").param(postingId)
                .query((rs, n) -> new P(rs.getString(1), rs.getString(2))).single();
        String subject = p.title() + " " + what;
        String body = detail + "\n\nPosting: " + (p.url() == null ? "" : p.url())
                + "\nYour card: " + appUrl + "/postings/" + postingId + "\n";
        notifications.fanOut(eventId, postingId, subject, body);
    }

    static String describe(RuleDiff.Diff diff) {
        StringBuilder sb = new StringBuilder();
        for (Criterion c : diff.removed()) {
            sb.append("Removed (").append(c.kind()).append(", ").append(c.source()).append("): \"")
                    .append(c.quote()).append("\"\n");
        }
        for (Criterion c : diff.added()) {
            sb.append("Added (").append(c.kind()).append(", ").append(c.source()).append("): \"")
                    .append(c.quote()).append("\"\n");
        }
        return sb.toString().strip();
    }

    private static void write(ArrayNode arr, List<Criterion> list) {
        for (Criterion c : list) {
            ObjectNode o = arr.addObject();
            o.put("kind", c.kind());
            o.put("source", c.source());
            o.put("quote", c.quote());
            o.put("value", c.value());
            o.put("range", c.range() == null ? null : c.range().toString());
        }
    }
}

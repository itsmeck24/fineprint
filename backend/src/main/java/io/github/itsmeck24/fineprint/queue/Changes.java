package io.github.itsmeck24.fineprint.queue;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.RuleDiff;
import io.github.itsmeck24.fineprint.store.ChangeStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// the one place change events are written. callers run it inside their own transaction.
@Component
public class Changes {

    private final ChangeStore store;
    private final JsonMapper json;

    public Changes(ChangeStore store, JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    public Optional<Long> rulesChanged(long postingId, long fromVersion, long toVersion, RuleDiff.Diff diff) {
        ObjectNode summary = json.createObjectNode();
        write(summary.putArray("added"), diff.added());
        write(summary.putArray("removed"), diff.removed());
        return store.record(postingId, "rules_changed", fromVersion, toVersion, json.writeValueAsString(summary),
                "rules:" + fromVersion + ":" + toVersion);
    }

    // closedSince identifies this closing, so the same close is never recorded twice
    public Optional<Long> closed(long postingId, String closedSince) {
        return store.record(postingId, "closed", null, null, "{}", "closed:" + postingId + ":" + closedSince);
    }

    public Optional<Long> reopened(long postingId, String closedAt) {
        return store.record(postingId, "reopened", null, null, "{}", "reopened:" + postingId + ":" + closedAt);
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

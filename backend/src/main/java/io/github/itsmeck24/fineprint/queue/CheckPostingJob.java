package io.github.itsmeck24.fineprint.queue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.source.BoardApi;
import io.github.itsmeck24.fineprint.store.PostingStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// decides whether a posting that dropped out of its board listing is really closed.
// closed means: a 404 on two checks at least close-after apart, and still not listed. anything else is
// a fetch failure, which marks the posting unreachable and retries, and never closes it.
@Component
public class CheckPostingJob implements JobHandler {

    private final BoardApi api;
    private final PostingStore store;
    private final Changes changes;
    private final Jobs jobs;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Duration closeAfter;

    public CheckPostingJob(BoardApi api, PostingStore store, Changes changes, Jobs jobs, TransactionTemplate tx,
                           JsonMapper json, FinePrintProperties props) {
        this.api = api;
        this.store = store;
        this.changes = changes;
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.closeAfter = props.collector().closeAfter();
    }

    @Override
    public String type() {
        return Jobs.CHECK_POSTING;
    }

    @Override
    public void run(JsonNode payload) throws Exception {
        long postingId = payload.path("postingId").asLong();
        PostingStore.PostingRef ref = store.ref(postingId)
                .orElseThrow(() -> new Permanent("posting " + postingId + " not found"));
        BoardApi.Snapshot s;
        try {
            s = ref.ats().equals("greenhouse")
                    ? api.greenhouseDetail(ref.token(), ref.externalId())
                    : api.leverPosting(ref.token(), ref.externalId());
        } catch (BoardApi.FetchFailed e) {
            if (e.status == 404 || e.status == 410) {
                missing(ref);
                return;
            }
            store.markUnreachable(postingId);
            throw e;
        } catch (IOException e) {
            store.markUnreachable(postingId);
            throw e;
        }
        found(postingId, s);
    }

    private void found(long postingId, BoardApi.Snapshot s) {
        tx.executeWithoutResult(status -> {
            PostingStore.Status st = store.status(postingId);
            if (st.status().equals("closed")) {
                changes.reopened(postingId, String.valueOf(st.closedAt().toEpochMilli()));
            }
            store.markOpen(postingId);
            String form = s.form() == null ? null : json.writeValueAsString(s.form());
            store.saveVersionIfChanged(postingId, s, form, json.writeValueAsString(s.raw()));
            store.markDetail(postingId, s.updatedAt());
        });
    }

    private void missing(PostingStore.PostingRef ref) {
        tx.executeWithoutResult(status -> {
            PostingStore.Status st = store.recordMiss(ref.id());
            if (st.status().equals("closed")) {
                return;
            }
            Instant now = store.dbNow();
            boolean longEnough = !st.firstMissAt().plus(closeAfter).isAfter(now);
            boolean stillUnlisted = store.lastPoll(ref.boardId())
                    .map(p -> st.lastSeenAt().isBefore(p)).orElse(true);
            if (st.missCount() >= 2 && longEnough && stillUnlisted) {
                store.markClosed(ref.id());
                changes.closed(ref.id(), String.valueOf(st.firstMissAt().toEpochMilli()));
            } else {
                jobs.checkPosting(ref.id(), closeAfter, "check:" + ref.id() + ":" + st.missCount());
            }
        });
    }
}

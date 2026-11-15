package io.github.itsmeck24.fineprint.queue;

import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.source.BoardApi;
import io.github.itsmeck24.fineprint.store.PostingStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FetchPostingJob implements JobHandler {

    private final BoardApi api;
    private final PostingStore store;
    private final Jobs jobs;
    private final JsonMapper json;

    public FetchPostingJob(BoardApi api, PostingStore store, Jobs jobs, JsonMapper json) {
        this.api = api;
        this.store = store;
        this.jobs = jobs;
        this.json = json;
    }

    @Override
    public String type() {
        return Jobs.FETCH_POSTING;
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
                // gone or never existed: the check job decides, with its two-miss rule
                jobs.checkPosting(postingId);
                return;
            }
            store.markUnreachable(postingId);
            throw e;
        } catch (java.io.IOException e) {
            store.markUnreachable(postingId);
            throw e;
        }
        String form = s.form() == null ? null : json.writeValueAsString(s.form());
        store.saveVersionIfChanged(postingId, s, form, json.writeValueAsString(s.raw()));
        store.markDetail(postingId);
    }
}

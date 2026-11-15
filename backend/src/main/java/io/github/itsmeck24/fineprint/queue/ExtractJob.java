package io.github.itsmeck24.fineprint.queue;

import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.rules.Rules;
import io.github.itsmeck24.fineprint.store.CriteriaStore;
import io.github.itsmeck24.fineprint.store.PostingStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ExtractJob implements JobHandler {

    private final PostingStore postings;
    private final CriteriaStore criteria;
    private final JsonMapper json;

    public ExtractJob(PostingStore postings, CriteriaStore criteria, JsonMapper json) {
        this.postings = postings;
        this.criteria = criteria;
        this.json = json;
    }

    @Override
    public String type() {
        return Jobs.EXTRACT;
    }

    @Override
    public void run(JsonNode payload) throws Exception {
        long versionId = payload.path("versionId").asLong();
        PostingStore.Version v = postings.version(versionId)
                .orElseThrow(() -> new Permanent("version " + versionId + " not found"));
        criteria.replace(versionId, Rules.NAME, Rules.extract(v.title(), v.bodyText(), v.form(), json));
    }
}

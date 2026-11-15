package io.github.itsmeck24.fineprint.queue;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.RuleDiff;
import io.github.itsmeck24.fineprint.rules.Rules;
import io.github.itsmeck24.fineprint.store.ChangeStore;
import io.github.itsmeck24.fineprint.store.CriteriaStore;
import io.github.itsmeck24.fineprint.store.PostingStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// runs the rules on one version, then compares with the versions next to it.
// versions can be extracted out of order by different workers, so it checks both neighbours,
// and the dedup key on change events stops the same pair from being reported twice.
@Component
public class ExtractJob implements JobHandler {

    private final PostingStore postings;
    private final CriteriaStore criteria;
    private final ChangeStore changesStore;
    private final Changes changes;
    private final Digests digests;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ExtractJob(PostingStore postings, CriteriaStore criteria, ChangeStore changesStore, Changes changes,
                      Digests digests, TransactionTemplate tx, JsonMapper json) {
        this.postings = postings;
        this.criteria = criteria;
        this.changesStore = changesStore;
        this.changes = changes;
        this.digests = digests;
        this.tx = tx;
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
        List<Criterion> found = Rules.extract(v.title(), v.bodyText(), v.form(), json);
        tx.executeWithoutResult(status -> {
            // one extraction per posting at a time, so neighbour checks see a consistent picture
            changesStore.lockPosting(v.postingId());
            criteria.replace(versionId, Rules.NAME, found);
            Optional<Long> prev = changesStore.previousExtracted(v.postingId(), versionId);
            Optional<Long> next = changesStore.nextExtracted(v.postingId(), versionId);
            prev.ifPresent(p -> compare(v.postingId(), p, versionId));
            next.ifPresent(n -> compare(v.postingId(), versionId, n));
            // the first version of a posting is when it counts as new for people watching its board
            if (!changesStore.hasEarlierVersion(v.postingId(), versionId)) {
                digests.offer(v.postingId(), v.title(), found);
            }
        });
    }

    private void compare(long postingId, long from, long to) {
        if (!changesStore.adjacentExtracted(postingId, from, to)) {
            return;
        }
        RuleDiff.Diff diff = RuleDiff.between(criteria.load(from, Rules.NAME), criteria.load(to, Rules.NAME));
        if (!diff.isEmpty()) {
            changes.rulesChanged(postingId, from, to, diff);
        }
    }
}

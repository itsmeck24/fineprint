package io.github.itsmeck24.fineprint.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.Eligibility;
import io.github.itsmeck24.fineprint.rules.Rules;
import io.github.itsmeck24.fineprint.store.ChangeStore;
import io.github.itsmeck24.fineprint.store.CriteriaStore;
import io.github.itsmeck24.fineprint.store.PostingStore;
import io.github.itsmeck24.fineprint.store.UserStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// builds what the app and the extension show for one posting and one user
@Component
public class PostingViews {

    public record Change(String kind, Instant at, JsonNode summary) {
    }

    public record Summary(long id, String title, String company, String ats, String url, String status,
                          Instant lastCheckedAt, int versions, boolean ready, List<Eligibility.Rule> rules,
                          List<Change> changes) {
    }

    private final JdbcClient jdbc;
    private final PostingStore postings;
    private final CriteriaStore criteria;
    private final ChangeStore changes;
    private final JsonMapper json;

    public PostingViews(JdbcClient jdbc, PostingStore postings, CriteriaStore criteria, ChangeStore changes,
                        JsonMapper json) {
        this.jdbc = jdbc;
        this.postings = postings;
        this.criteria = criteria;
        this.changes = changes;
        this.json = json;
    }

    public Summary summary(UserStore.User user, long postingId) {
        record Row(String title, String token, String ats, String url, Timestamp checked, int versions,
                   String status) {
        }
        Row r = jdbc.sql("""
                        select p.title, b.token, b.ats, p.url, p.last_detail_at,
                               (select count(*) from posting_versions v where v.posting_id = p.id)::int,
                               p.status
                        from postings p join boards b on b.id = p.board_id where p.id = ?""")
                .param(postingId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5), rs.getInt(6), rs.getString(7)))
                .single();
        Optional<Long> latest = postings.latestVersionId(postingId);
        boolean ready = latest.isPresent() && extracted(latest.get());
        List<Criterion> found = ready ? criteria.load(latest.get(), Rules.NAME) : List.of();
        List<Eligibility.Rule> rules = ready
                ? Eligibility.card(new Eligibility.Profile(user.gradMonth(), user.degreeLevel()), r.title(), found)
                : List.of();
        List<Change> history = changes.forPosting(postingId).stream()
                .map(e -> new Change(e.kind(), e.createdAt(), json.readTree(e.summary()))).toList();
        return new Summary(postingId, r.title(), r.token(), r.ats(), r.url(), r.status(),
                r.checked() == null ? null : r.checked().toInstant(), r.versions(), ready, rules, history);
    }

    private boolean extracted(long versionId) {
        return jdbc.sql("select extracted_at is not null from posting_versions where id = ?").param(versionId)
                .query(Boolean.class).single();
    }
}

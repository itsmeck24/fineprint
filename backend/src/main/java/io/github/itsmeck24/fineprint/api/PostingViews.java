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
import io.github.itsmeck24.fineprint.store.CriteriaStore;
import io.github.itsmeck24.fineprint.store.PostingStore;
import io.github.itsmeck24.fineprint.store.UserStore;

// builds what the app and the extension show for one posting and one user
@Component
public class PostingViews {

    public record Summary(long id, String title, String company, String ats, String url, Instant lastCheckedAt,
                          int versions, boolean ready, List<Eligibility.Rule> rules) {
    }

    private final JdbcClient jdbc;
    private final PostingStore postings;
    private final CriteriaStore criteria;

    public PostingViews(JdbcClient jdbc, PostingStore postings, CriteriaStore criteria) {
        this.jdbc = jdbc;
        this.postings = postings;
        this.criteria = criteria;
    }

    public Summary summary(UserStore.User user, long postingId) {
        record Row(String title, String token, String ats, String url, Timestamp checked, int versions) {
        }
        Row r = jdbc.sql("""
                        select p.title, b.token, b.ats, p.url, p.last_detail_at,
                               (select count(*) from posting_versions v where v.posting_id = p.id)::int
                        from postings p join boards b on b.id = p.board_id where p.id = ?""")
                .param(postingId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5), rs.getInt(6)))
                .single();
        Optional<Long> latest = postings.latestVersionId(postingId);
        boolean ready = latest.isPresent() && extracted(latest.get());
        List<Criterion> found = ready ? criteria.load(latest.get(), Rules.NAME) : List.of();
        List<Eligibility.Rule> rules = ready
                ? Eligibility.card(new Eligibility.Profile(user.gradMonth(), user.degreeLevel()), r.title(), found)
                : List.of();
        return new Summary(postingId, r.title(), r.token(), r.ats(), r.url(),
                r.checked() == null ? null : r.checked().toInstant(), r.versions(), ready, rules);
    }

    private boolean extracted(long versionId) {
        return jdbc.sql("select extracted_at is not null from posting_versions where id = ?").param(versionId)
                .query(Boolean.class).single();
    }
}

package io.github.itsmeck24.fineprint.queue;

import java.sql.Date;
import java.time.YearMonth;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.Eligibility;

// picks the watchers a brand-new posting should be offered to. runs inside the extract transaction.
@Component
public class Digests {

    private final JdbcClient jdbc;

    public Digests(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Watcher(long userId, YearMonth gradMonth, String degree) {
    }

    // only postings first seen after the watch started count as new, so watching a board
    // does not flood the first digest with everything already on it
    public int offer(long postingId, String title, List<Criterion> criteria) {
        List<Watcher> watchers = jdbc.sql("""
                        select u.id, u.grad_month, u.degree_level
                        from postings p
                        join watches w on w.board_id = p.board_id
                        join users u on u.id = w.user_id
                        where p.id = ? and p.first_seen_at > w.created_at""")
                .param(postingId)
                .query((rs, n) -> new Watcher(rs.getLong(1), YearMonth.from(((Date) rs.getDate(2)).toLocalDate()),
                        rs.getString(3)))
                .list();
        int added = 0;
        for (Watcher w : watchers) {
            if (ruledOut(Eligibility.card(new Eligibility.Profile(w.gradMonth(), w.degree()), title, criteria))) {
                continue;
            }
            added += jdbc.sql("insert into digest_items (user_id, posting_id) values (?, ?) on conflict do nothing")
                    .params(w.userId(), postingId).update();
        }
        return added;
    }

    // a stated rule that excludes the user. unknown and screened stay in, the user decides those.
    static boolean ruledOut(List<Eligibility.Rule> card) {
        return card.stream().anyMatch(r -> r.label().equals(Eligibility.CONFLICTS));
    }
}

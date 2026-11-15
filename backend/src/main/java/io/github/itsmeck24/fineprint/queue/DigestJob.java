package io.github.itsmeck24.fineprint.queue;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import tools.jackson.databind.JsonNode;

// one digest email per user with new postings. the items are locked, the notification is written, and the items
// are marked in one transaction, so a crash leaves either all of it or none of it.
@Component
public class DigestJob implements JobHandler {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final String appUrl;

    public DigestJob(JdbcClient jdbc, TransactionTemplate tx, FinePrintProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.appUrl = props.mail().appUrl();
    }

    @Override
    public String type() {
        return Jobs.DIGESTS;
    }

    @Override
    public void run(JsonNode payload) {
        List<Long> users = jdbc.sql("select distinct user_id from digest_items where notification_id is null")
                .query(Long.class).list();
        for (long userId : users) {
            tx.executeWithoutResult(s -> one(userId));
        }
    }

    record Item(long postingId, String title, String url, String company) {
    }

    void one(long userId) {
        List<Item> items = jdbc.sql("""
                        select d.posting_id, p.title, p.url, b.token from digest_items d
                        join postings p on p.id = d.posting_id join boards b on b.id = p.board_id
                        where d.user_id = ? and d.notification_id is null
                        order by d.posting_id
                        for update of d""")
                .param(userId)
                .query((rs, n) -> new Item(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list();
        if (items.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder("New postings on boards you watch that do not rule you out:\n\n");
        for (Item i : items) {
            body.append("- ").append(i.company()).append(": ").append(i.title()).append("\n  ")
                    .append(i.url() == null ? "" : i.url()).append("\n  card: ").append(appUrl)
                    .append("/postings/").append(i.postingId()).append("\n");
        }
        String subject = items.size() == 1 ? "1 new posting you can apply to" : items.size() + " new postings you can apply to";
        String key = "digest:" + userId + ":" + items.get(0).postingId() + ":" + items.get(items.size() - 1).postingId()
                + ":" + items.size();
        Long notificationId = jdbc.sql("""
                        insert into notifications (user_id, kind, dedup_key, subject, body)
                        values (?, 'digest', ?, ?, ?) on conflict (dedup_key) do nothing returning id""")
                .params(userId, key, subject, body.toString())
                .query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("select id from notifications where dedup_key = ?").param(key)
                        .query(Long.class).single());
        jdbc.sql("""
                        update digest_items set notification_id = ?
                        where user_id = ? and notification_id is null and posting_id = any(?)""")
                .params(notificationId, userId, items.stream().map(Item::postingId).toArray(Long[]::new))
                .update();
    }
}

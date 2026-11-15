package io.github.itsmeck24.fineprint;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostingStore {

    public record Existing(long id, Instant sourceUpdatedAt, Instant lastDetailAt) {
    }

    private final JdbcClient jdbc;

    public PostingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long boardId(String ats, String token) {
        jdbc.sql("insert into boards (ats, token) values (?, ?) on conflict do nothing").params(ats, token).update();
        return jdbc.sql("select id from boards where ats = ? and token = ?").params(ats, token)
                .query(Long.class).single();
    }

    public Optional<Existing> find(long boardId, String externalId) {
        return jdbc.sql("""
                        select id, source_updated_at, last_detail_at from postings
                        where board_id = ? and external_id = ?""")
                .params(boardId, externalId)
                .query((rs, n) -> new Existing(rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .optional();
    }

    public long upsertPosting(long boardId, String externalId, String internalJobId, String title, String url,
                              Instant sourceUpdatedAt) {
        return jdbc.sql("""
                        insert into postings (board_id, external_id, internal_job_id, title, url, source_updated_at)
                        values (?, ?, ?, ?, ?, ?)
                        on conflict (board_id, external_id) do update set
                            internal_job_id = coalesce(excluded.internal_job_id, postings.internal_job_id),
                            title = excluded.title,
                            url = coalesce(excluded.url, postings.url),
                            source_updated_at = coalesce(excluded.source_updated_at, postings.source_updated_at),
                            last_seen_at = now()
                        returning id""")
                .params(boardId, externalId, internalJobId, title, url, ts(sourceUpdatedAt))
                .query(Long.class).single();
    }

    // compares against the latest version only, so A -> B -> A keeps all three.
    // the row lock on the posting stops two writers from both inserting the same new version.
    @Transactional
    public boolean saveVersionIfChanged(long postingId, BoardApi.Snapshot s, String formJson, String rawJson) {
        jdbc.sql("select id from postings where id = ? for update").param(postingId).query(Long.class).single();
        String hash = PostingText.hash(s.title(), s.location(), s.bodyText(), formJson);
        Optional<String> latest = jdbc.sql("""
                        select content_hash from posting_versions
                        where posting_id = ? order by id desc limit 1""")
                .param(postingId).query(String.class).optional();
        if (latest.isPresent() && latest.get().equals(hash)) {
            return false;
        }
        jdbc.sql("""
                        insert into posting_versions (posting_id, content_hash, title, location, body_text, form, raw)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""")
                .params(postingId, hash, s.title(), s.location(), s.bodyText(), formJson, rawJson)
                .update();
        if (s.firstPublished() != null) {
            jdbc.sql("update postings set first_published_at = coalesce(first_published_at, ?) where id = ?")
                    .params(ts(s.firstPublished()), postingId).update();
        }
        return true;
    }

    public void markDetail(long postingId) {
        jdbc.sql("update postings set last_detail_at = now() where id = ?").param(postingId).update();
    }

    public void recordPoll(long boardId, int jobCount, int keptCount) {
        jdbc.sql("insert into board_polls (board_id, job_count, kept_count) values (?, ?, ?)")
                .params(boardId, jobCount, keptCount).update();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

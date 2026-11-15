package io.github.itsmeck24.fineprint.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.itsmeck24.fineprint.queue.Jobs;
import io.github.itsmeck24.fineprint.source.BoardApi;
import io.github.itsmeck24.fineprint.source.PostingText;

@Repository
public class PostingStore {

    public record Board(long id, String ats, String token) {
    }

    public record Existing(long id, Instant sourceUpdatedAt, Instant lastDetailAt) {
    }

    public record PostingRef(long id, long boardId, String ats, String token, String externalId) {
    }

    public record Status(String status, int missCount, Instant firstMissAt, Instant closedAt, Instant lastSeenAt) {
    }

    public record Version(long id, long postingId, Instant fetchedAt, String title, String location, String bodyText,
                          String form) {
    }

    private final JdbcClient jdbc;
    private final Jobs jobs;

    public PostingStore(JdbcClient jdbc, Jobs jobs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
    }

    public long boardId(String ats, String token) {
        jdbc.sql("insert into boards (ats, token) values (?, ?) on conflict do nothing").params(ats, token).update();
        return jdbc.sql("select id from boards where ats = ? and token = ?").params(ats, token)
                .query(Long.class).single();
    }

    public Board board(long id) {
        return jdbc.sql("select id, ats, token from boards where id = ?").param(id)
                .query((rs, n) -> new Board(rs.getLong(1), rs.getString(2), rs.getString(3))).single();
    }

    public Optional<Existing> find(long boardId, String externalId) {
        return jdbc.sql("""
                        select id, source_updated_at, last_detail_at from postings
                        where board_id = ? and external_id = ?""")
                .params(boardId, externalId)
                .query((rs, n) -> new Existing(rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .optional();
    }

    public Optional<PostingRef> ref(long postingId) {
        return jdbc.sql("""
                        select p.id, b.id, b.ats, b.token, p.external_id
                        from postings p join boards b on b.id = p.board_id where p.id = ?""")
                .param(postingId)
                .query((rs, n) -> new PostingRef(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .optional();
    }

    // greenhouse company career urls only carry gh_jid, so find the board from postings we already know
    public List<PostingRef> findGreenhouseByExternalId(String externalId) {
        return jdbc.sql("""
                        select p.id, b.id, b.ats, b.token, p.external_id
                        from postings p join boards b on b.id = p.board_id
                        where b.ats = 'greenhouse' and p.external_id = ?""")
                .param(externalId)
                .query((rs, n) -> new PostingRef(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)))
                .list();
    }

    // seen in a board listing: updates the row and marks it seen now
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
                            last_seen_at = now(),
                            -- listed again, so earlier misses and fetch errors no longer count. closed stays closed
                            -- until a check confirms the posting is back
                            miss_count = 0,
                            first_miss_at = null,
                            status = case when postings.status = 'unreachable' then 'open' else postings.status end
                        returning id""")
                .params(boardId, externalId, internalJobId, title, url, ts(sourceUpdatedAt))
                .query(Long.class).single();
    }

    // saved by a user before we have seen it: create the row without touching last_seen_at of an existing one
    public long ensurePosting(long boardId, String externalId) {
        jdbc.sql("""
                        insert into postings (board_id, external_id, title) values (?, ?, '')
                        on conflict (board_id, external_id) do nothing""")
                .params(boardId, externalId).update();
        return find(boardId, externalId).orElseThrow().id();
    }

    public java.util.Set<String> trackedExternalIds(long boardId) {
        return new java.util.HashSet<>(jdbc.sql("select external_id from postings where board_id = ?").param(boardId)
                .query(String.class).list());
    }

    public Instant dbNow() {
        return jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
    }

    // tracked postings of a board that the latest listing did not include
    public List<Long> missingFromListing(long boardId, Instant listedAt) {
        return jdbc.sql("select id from postings where board_id = ? and last_seen_at < ? and status <> 'closed'")
                .params(boardId, ts(listedAt)).query(Long.class).list();
    }

    public List<Long> closedButListed(long boardId, Instant listedAt) {
        return jdbc.sql("select id from postings where board_id = ? and last_seen_at >= ? and status = 'closed'")
                .params(boardId, ts(listedAt)).query(Long.class).list();
    }

    public Status status(long postingId) {
        return jdbc.sql("""
                        select status, miss_count, first_miss_at, closed_at, last_seen_at
                        from postings where id = ? for update""")
                .param(postingId)
                .query((rs, n) -> new Status(rs.getString(1), rs.getInt(2), instant(rs.getTimestamp(3)),
                        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5))))
                .single();
    }

    public Status recordMiss(long postingId) {
        jdbc.sql("update postings set miss_count = miss_count + 1, first_miss_at = coalesce(first_miss_at, now()) where id = ?")
                .param(postingId).update();
        return status(postingId);
    }

    public void markClosed(long postingId) {
        jdbc.sql("update postings set status = 'closed', closed_at = now() where id = ?").param(postingId).update();
    }

    public void markOpen(long postingId) {
        jdbc.sql("update postings set status = 'open', miss_count = 0, first_miss_at = null, closed_at = null where id = ?")
                .param(postingId).update();
    }

    // a failed fetch never closes a posting
    public void markUnreachable(long postingId) {
        jdbc.sql("update postings set status = 'unreachable' where id = ? and status = 'open'").param(postingId).update();
    }

    public Optional<Instant> lastPoll(long boardId) {
        return jdbc.sql("select polled_at from board_polls where board_id = ? order by id desc limit 1").param(boardId)
                .query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    public boolean savedByAnyone(long boardId, String externalId) {
        return jdbc.sql("""
                        select exists (select 1 from saved_postings s join postings p on p.id = s.posting_id
                                       where p.board_id = ? and p.external_id = ?)""")
                .params(boardId, externalId).query(Boolean.class).single();
    }

    // compares against the latest version only, so A -> B -> A keeps all three.
    // the row lock on the posting stops two writers from both inserting the same new version.
    // the extract job is queued in the same transaction, so a saved version is never left without one.
    @Transactional
    public Optional<Long> saveVersionIfChanged(long postingId, BoardApi.Snapshot s, String formJson, String rawJson) {
        jdbc.sql("select id from postings where id = ? for update").param(postingId).query(Long.class).single();
        String hash = PostingText.hash(s.title(), s.location(), s.bodyText(), formJson);
        Optional<String> latest = jdbc.sql("""
                        select content_hash from posting_versions
                        where posting_id = ? order by id desc limit 1""")
                .param(postingId).query(String.class).optional();
        if (s.title() != null && !s.title().isBlank()) {
            jdbc.sql("update postings set title = ? where id = ?").params(s.title(), postingId).update();
        }
        if (s.firstPublished() != null) {
            jdbc.sql("update postings set first_published_at = coalesce(first_published_at, ?) where id = ?")
                    .params(ts(s.firstPublished()), postingId).update();
        }
        if (s.url() != null) {
            jdbc.sql("update postings set url = coalesce(url, ?) where id = ?").params(s.url(), postingId).update();
        }
        if (latest.isPresent() && latest.get().equals(hash)) {
            return Optional.empty();
        }
        long id = jdbc.sql("""
                        insert into posting_versions (posting_id, content_hash, title, location, body_text, form, raw)
                        values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) returning id""")
                .params(postingId, hash, s.title(), s.location(), s.bodyText(), formJson, rawJson)
                .query(Long.class).single();
        jobs.extract(id);
        return Optional.of(id);
    }

    public Optional<Version> version(long versionId) {
        return jdbc.sql("""
                        select id, posting_id, fetched_at, title, location, body_text, form::text
                        from posting_versions where id = ?""")
                .param(versionId)
                .query((rs, n) -> new Version(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)))
                .optional();
    }

    public Optional<Long> latestVersionId(long postingId) {
        return jdbc.sql("select id from posting_versions where posting_id = ? order by id desc limit 1").param(postingId)
                .query(Long.class).optional();
    }

    public void markDetail(long postingId) {
        jdbc.sql("update postings set last_detail_at = now() where id = ?").param(postingId).update();
    }

    public void recordPoll(long boardId, int jobCount, int keptCount) {
        jdbc.sql("insert into board_polls (board_id, job_count, kept_count) values (?, ?, ?)")
                .params(boardId, jobCount, keptCount).update();
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

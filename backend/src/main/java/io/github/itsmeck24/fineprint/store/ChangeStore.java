package io.github.itsmeck24.fineprint.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ChangeStore {

    public record Event(long id, long postingId, String kind, Long fromVersionId, Long toVersionId, String summary,
                        Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public ChangeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // returns the new event id, or empty when this change was already recorded
    public Optional<Long> record(long postingId, String kind, Long fromVersion, Long toVersion, String summaryJson,
                                 String dedupKey) {
        return jdbc.sql("""
                        insert into change_events (posting_id, kind, from_version_id, to_version_id, summary, dedup_key)
                        values (?, ?, ?, ?, ?::jsonb, ?)
                        on conflict (dedup_key) do nothing
                        returning id""")
                .params(postingId, kind, fromVersion, toVersion, summaryJson, dedupKey)
                .query(Long.class).optional();
    }

    public List<Event> forPosting(long postingId) {
        return jdbc.sql("""
                        select id, posting_id, kind, from_version_id, to_version_id, summary::text, created_at
                        from change_events where posting_id = ? order by id""")
                .param(postingId)
                .query((rs, n) -> new Event(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        (Long) rs.getObject(4), (Long) rs.getObject(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant()))
                .list();
    }

    // neighbours of a version that already have rules, used to compare in both directions
    public Optional<Long> previousExtracted(long postingId, long versionId) {
        return jdbc.sql("""
                        select id from posting_versions
                        where posting_id = ? and id < ? and extracted_at is not null
                        order by id desc limit 1""")
                .params(postingId, versionId).query(Long.class).optional();
    }

    public Optional<Long> nextExtracted(long postingId, long versionId) {
        return jdbc.sql("""
                        select id from posting_versions
                        where posting_id = ? and id > ? and extracted_at is not null
                        order by id limit 1""")
                .params(postingId, versionId).query(Long.class).optional();
    }

    // any version between the two that is not extracted yet means the pair is not adjacent
    public boolean adjacentExtracted(long postingId, long from, long to) {
        return jdbc.sql("""
                        select not exists (select 1 from posting_versions
                                           where posting_id = ? and id > ? and id < ?)""")
                .params(postingId, from, to).query(Boolean.class).single();
    }

    public void lockPosting(long postingId) {
        jdbc.sql("select id from postings where id = ? for update").param(postingId).query(Long.class).single();
    }
}

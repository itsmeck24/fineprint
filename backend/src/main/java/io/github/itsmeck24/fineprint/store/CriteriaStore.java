package io.github.itsmeck24.fineprint.store;

import java.sql.Date;
import java.time.YearMonth;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import io.github.itsmeck24.fineprint.rules.Criterion;
import io.github.itsmeck24.fineprint.rules.DateRanges;

@Repository
public class CriteriaStore {

    private final JdbcClient jdbc;

    public CriteriaStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // delete and insert in one transaction, so running extraction twice gives the same rows
    @Transactional
    public void replace(long versionId, String extractor, List<Criterion> criteria) {
        jdbc.sql("delete from criteria where version_id = ? and extractor = ?").params(versionId, extractor).update();
        for (Criterion c : criteria) {
            jdbc.sql("""
                            insert into criteria (version_id, kind, source, quote, range_start, range_end, value, extractor)
                            values (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(versionId, c.kind(), c.source(), c.quote(),
                            c.range() == null ? null : date(c.range().start()),
                            c.range() == null ? null : date(c.range().end()),
                            c.value(), extractor)
                    .update();
        }
        jdbc.sql("update posting_versions set extracted_at = now() where id = ?").param(versionId).update();
    }

    public List<Criterion> load(long versionId, String extractor) {
        return jdbc.sql("""
                        select kind, source, quote, range_start, range_end, value from criteria
                        where version_id = ? and extractor = ? order by id""")
                .params(versionId, extractor)
                .query((rs, n) -> {
                    YearMonth s = month(rs.getDate(4));
                    YearMonth e = month(rs.getDate(5));
                    DateRanges.Range r = s == null && e == null ? null : new DateRanges.Range(s, e);
                    return new Criterion(rs.getString(1), rs.getString(2), rs.getString(3), r, rs.getString(6));
                })
                .list();
    }

    private static Date date(YearMonth m) {
        return m == null ? null : Date.valueOf(m.atDay(1));
    }

    private static YearMonth month(Date d) {
        return d == null ? null : YearMonth.from(d.toLocalDate());
    }
}

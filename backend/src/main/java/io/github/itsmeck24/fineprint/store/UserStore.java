package io.github.itsmeck24.fineprint.store;

import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// a user is only an email, a password hash, a graduation month, and a degree level
@Repository
public class UserStore {

    public record User(long id, String email, String passwordHash, YearMonth gradMonth, String degreeLevel) {
    }

    private final JdbcClient jdbc;

    public UserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> create(String email, String passwordHash, YearMonth gradMonth, String degreeLevel) {
        try {
            return Optional.of(jdbc.sql("""
                            insert into users (email, password_hash, grad_month, degree_level)
                            values (?, ?, ?, ?) returning id""")
                    .params(email, passwordHash, Date.valueOf(gradMonth.atDay(1)), degreeLevel)
                    .query(Long.class).single());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public Optional<User> byEmail(String email) {
        return one("select id, email, password_hash, grad_month, degree_level from users where email = ?", email);
    }

    public Optional<User> byId(long id) {
        return one("select id, email, password_hash, grad_month, degree_level from users where id = ?", id);
    }

    private Optional<User> one(String sql, Object arg) {
        return jdbc.sql(sql).param(arg)
                .query((rs, n) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3),
                        YearMonth.from(rs.getDate(4).toLocalDate()), rs.getString(5)))
                .optional();
    }

    public void updateProfile(long id, YearMonth gradMonth, String degreeLevel) {
        jdbc.sql("update users set grad_month = ?, degree_level = ? where id = ?")
                .params(Date.valueOf(gradMonth.atDay(1)), degreeLevel, id).update();
    }

    // saved postings, watches and notifications go with it through on delete cascade
    public void delete(long id) {
        jdbc.sql("delete from users where id = ?").param(id).update();
    }

    public boolean save(long userId, long postingId) {
        return jdbc.sql("insert into saved_postings (user_id, posting_id) values (?, ?) on conflict do nothing")
                .params(userId, postingId).update() == 1;
    }

    public boolean unsave(long userId, long postingId) {
        return jdbc.sql("delete from saved_postings where user_id = ? and posting_id = ?")
                .params(userId, postingId).update() == 1;
    }

    public boolean isSaved(long userId, long postingId) {
        return jdbc.sql("select exists (select 1 from saved_postings where user_id = ? and posting_id = ?)")
                .params(userId, postingId).query(Boolean.class).single();
    }

    public List<Long> savedPostingIds(long userId) {
        return jdbc.sql("select posting_id from saved_postings where user_id = ? order by saved_at desc")
                .param(userId).query(Long.class).list();
    }
}

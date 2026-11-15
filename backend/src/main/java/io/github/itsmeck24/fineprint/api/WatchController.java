package io.github.itsmeck24.fineprint.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.itsmeck24.fineprint.queue.Jobs;
import io.github.itsmeck24.fineprint.store.PostingStore;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/watches")
public class WatchController {

    public record AddRequest(String board) {
    }

    public record Watch(long boardId, String ats, String token, Instant since, int openPostings) {
    }

    private final JdbcClient jdbc;
    private final PostingStore postings;
    private final Jobs jobs;

    public WatchController(JdbcClient jdbc, PostingStore postings, Jobs jobs) {
        this.jdbc = jdbc;
        this.postings = postings;
        this.jobs = jobs;
    }

    @GetMapping
    public List<Watch> list(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.sql("""
                        select b.id, b.ats, b.token, w.created_at,
                               (select count(*) from postings p where p.board_id = b.id and p.status <> 'closed')::int
                        from watches w join boards b on b.id = w.board_id
                        where w.user_id = ? order by b.token""")
                .param(userId(jwt))
                .query((rs, n) -> new Watch(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getInt(5)))
                .list();
    }

    // accepts "greenhouse:verkada", "lever:palantir", or a board link like https://job-boards.greenhouse.io/verkada
    @PostMapping
    public ResponseEntity<Map<String, Long>> add(@AuthenticationPrincipal Jwt jwt, @RequestBody AddRequest req) {
        String[] board = parseBoard(req.board()).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "use greenhouse:<board>, lever:<company>, or a board link"));
        long boardId = postings.boardId(board[0], board[1]);
        jdbc.sql("insert into watches (user_id, board_id) values (?, ?) on conflict do nothing")
                .params(userId(jwt), boardId).update();
        jobs.pollBoard(boardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("boardId", boardId));
    }

    @DeleteMapping("/{boardId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long boardId) {
        int n = jdbc.sql("delete from watches where user_id = ? and board_id = ?").params(userId(jwt), boardId).update();
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    static Optional<String[]> parseBoard(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String v = s.strip();
        String ats;
        String token;
        if (v.startsWith("http://") || v.startsWith("https://")) {
            URI u;
            try {
                u = URI.create(v);
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            String[] parts = u.getPath() == null ? new String[0] : u.getPath().split("/");
            if (parts.length < 2) {
                return Optional.empty();
            }
            if (host.endsWith("greenhouse.io")) {
                ats = "greenhouse";
            } else if (host.equals("jobs.lever.co") || host.equals("jobs.eu.lever.co")) {
                ats = "lever";
            } else {
                return Optional.empty();
            }
            token = parts[1];
        } else {
            String[] parts = v.split(":", 2);
            if (parts.length != 2) {
                return Optional.empty();
            }
            ats = parts[0].toLowerCase();
            token = parts[1];
        }
        if (!ats.equals("greenhouse") && !ats.equals("lever") || !PostingUrls.TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        return Optional.of(new String[] {ats, token});
    }

    private static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}

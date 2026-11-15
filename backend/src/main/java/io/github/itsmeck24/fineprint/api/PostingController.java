package io.github.itsmeck24.fineprint.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.itsmeck24.fineprint.queue.Jobs;
import io.github.itsmeck24.fineprint.store.PostingStore;
import io.github.itsmeck24.fineprint.store.UserStore;

@RestController
@ConditionalOnWebApplication
@RequestMapping("/api/postings")
public class PostingController {

    public record SaveRequest(String url) {
    }

    private final UserStore users;
    private final PostingStore postings;
    private final PostingViews views;
    private final Jobs jobs;

    public PostingController(UserStore users, PostingStore postings, PostingViews views, Jobs jobs) {
        this.users = users;
        this.postings = postings;
        this.views = views;
        this.jobs = jobs;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Long>> save(@AuthenticationPrincipal Jwt jwt, @RequestBody SaveRequest req) {
        UserStore.User user = me(jwt);
        PostingUrls.Parsed p = PostingUrls.parse(req.url())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "paste a job-boards.greenhouse.io or jobs.lever.co link"));
        long postingId = resolve(p).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                "this careers page hides the board name, open the job-boards.greenhouse.io link instead"));
        users.save(user.id(), postingId);
        jobs.fetchPosting(postingId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", postingId));
    }

    @GetMapping
    public List<PostingViews.Summary> list(@AuthenticationPrincipal Jwt jwt) {
        UserStore.User user = me(jwt);
        return users.savedPostingIds(user.id()).stream().map(id -> views.summary(user, id)).toList();
    }

    @GetMapping("/{id}")
    public PostingViews.Summary one(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        UserStore.User user = me(jwt);
        // someone else's posting looks the same as a missing one
        if (!users.isSaved(user.id(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return views.summary(user, id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unsave(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        UserStore.User user = me(jwt);
        if (!users.unsave(user.id(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    // for the browser extension: is this page a posting we already know, and is it saved
    @GetMapping("/lookup")
    public Map<String, Object> lookup(@AuthenticationPrincipal Jwt jwt, @RequestParam String url) {
        UserStore.User user = me(jwt);
        PostingUrls.Parsed p = PostingUrls.parse(url)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Optional<Long> id = known(p);
        if (id.isEmpty()) {
            return Map.of("known", false);
        }
        return Map.of("known", true, "saved", users.isSaved(user.id(), id.get()), "posting", views.summary(user, id.get()));
    }

    private Optional<Long> resolve(PostingUrls.Parsed p) {
        if (p.boardKnown()) {
            return Optional.of(postings.ensurePosting(postings.boardId(p.ats(), p.token()), p.externalId()));
        }
        return known(p);
    }

    private Optional<Long> known(PostingUrls.Parsed p) {
        if (p.boardKnown()) {
            long boardId = postings.boardId(p.ats(), p.token());
            return postings.find(boardId, p.externalId()).map(PostingStore.Existing::id);
        }
        List<PostingStore.PostingRef> refs = postings.findGreenhouseByExternalId(p.externalId());
        return refs.size() == 1 ? Optional.of(refs.get(0).id()) : Optional.empty();
    }

    private UserStore.User me(Jwt jwt) {
        return users.byId(Long.parseLong(jwt.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}

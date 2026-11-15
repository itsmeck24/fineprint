package io.github.itsmeck24.fineprint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

// polls each watched board once per run and stores a new version whenever a posting's content changes.
// milestone 0 runs this from a timer. later the same steps become jobs in the postgres queue.
@Component
@ConditionalOnProperty(name = "fineprint.collector.enabled", havingValue = "true")
public class Collector {

    private static final Logger log = LoggerFactory.getLogger(Collector.class);

    private final CollectorProperties props;
    private final BoardApi api;
    private final PostingStore store;
    private final JsonMapper json;
    private final Pattern include;
    private final Pattern exclude;

    public Collector(CollectorProperties props, BoardApi api, PostingStore store, JsonMapper json) {
        this.props = props;
        this.api = api;
        this.store = store;
        this.json = json;
        this.include = Pattern.compile(props.titleInclude());
        this.exclude = Pattern.compile(props.titleExclude());
    }

    @Scheduled(initialDelayString = "PT10S", fixedDelayString = "${fineprint.collector.poll-every}")
    public void run() {
        for (String board : props.boards()) {
            String[] parts = board.split(":", 2);
            try {
                if (parts[0].equals("greenhouse")) {
                    pollGreenhouse(parts[1]);
                } else if (parts[0].equals("lever")) {
                    pollLever(parts[1]);
                } else {
                    log.warn("unknown board type {}", board);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // one bad board should not stop the others
                log.warn("poll failed for {}: {}", board, e.toString());
            }
        }
    }

    boolean wanted(String title) {
        return include.matcher(title).find() && !exclude.matcher(title).find();
    }

    void pollGreenhouse(String token) throws Exception {
        long boardId = store.boardId("greenhouse", token);
        List<BoardApi.ListItem> jobs = api.greenhouseList(token);
        int kept = 0;
        int changed = 0;
        Instant staleBefore = Instant.now().minus(props.detailRefresh());
        for (BoardApi.ListItem job : jobs) {
            if (!wanted(job.title())) {
                continue;
            }
            kept++;
            Optional<PostingStore.Existing> before = store.find(boardId, job.externalId());
            long postingId = store.upsertPosting(boardId, job.externalId(), job.internalJobId(), job.title(),
                    job.url(), job.updatedAt());
            boolean needDetail = before.isEmpty()
                    || !Objects.equals(before.get().sourceUpdatedAt(), job.updatedAt())
                    || before.get().lastDetailAt() == null
                    || before.get().lastDetailAt().isBefore(staleBefore);
            if (!needDetail) {
                continue;
            }
            try {
                if (save(postingId, api.greenhouseDetail(token, job.externalId()))) {
                    changed++;
                }
                store.markDetail(postingId);
            } catch (BoardApi.FetchFailed e) {
                // a 404 here may mean closed. milestone 2 decides that, for now it is only logged
                log.info("detail failed for greenhouse {} {}: {}", token, job.externalId(), e.getMessage());
            }
        }
        store.recordPoll(boardId, jobs.size(), kept);
        log.info("greenhouse {}: {} jobs, {} early-career, {} new versions", token, jobs.size(), kept, changed);
    }

    void pollLever(String site) throws Exception {
        long boardId = store.boardId("lever", site);
        List<BoardApi.Snapshot> postings = api.leverList(site);
        int kept = 0;
        int changed = 0;
        for (BoardApi.Snapshot p : postings) {
            if (!wanted(p.title())) {
                continue;
            }
            kept++;
            long postingId = store.upsertPosting(boardId, p.externalId(), null, p.title(), p.url(), null);
            if (save(postingId, p)) {
                changed++;
            }
            store.markDetail(postingId);
        }
        store.recordPoll(boardId, postings.size(), kept);
        log.info("lever {}: {} postings, {} early-career, {} new versions", site, postings.size(), kept, changed);
    }

    private boolean save(long postingId, BoardApi.Snapshot s) {
        String form = s.form() == null ? null : json.writeValueAsString(s.form());
        return store.saveVersionIfChanged(postingId, s, form, json.writeValueAsString(s.raw()));
    }
}

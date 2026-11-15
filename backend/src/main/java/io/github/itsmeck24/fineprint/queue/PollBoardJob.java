package io.github.itsmeck24.fineprint.queue;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.source.BoardApi;
import io.github.itsmeck24.fineprint.store.PostingStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// lists one board. greenhouse: queue a detail fetch for new or changed postings.
// lever: the list already has full content, so versions are saved here.
@Component
public class PollBoardJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PollBoardJob.class);

    private final BoardApi api;
    private final PostingStore store;
    private final Jobs jobs;
    private final JsonMapper json;
    private final FinePrintProperties.Collector props;
    private final Pattern include;
    private final Pattern exclude;

    public PollBoardJob(BoardApi api, PostingStore store, Jobs jobs, JsonMapper json, FinePrintProperties props) {
        this.api = api;
        this.store = store;
        this.jobs = jobs;
        this.json = json;
        this.props = props.collector();
        this.include = Pattern.compile(this.props.titleInclude());
        this.exclude = Pattern.compile(this.props.titleExclude());
    }

    @Override
    public String type() {
        return Jobs.POLL_BOARD;
    }

    public boolean wanted(String title) {
        return include.matcher(title).find() && !exclude.matcher(title).find();
    }

    @Override
    public void run(JsonNode payload) throws Exception {
        PostingStore.Board board = store.board(payload.path("boardId").asLong());
        if (board.ats().equals("greenhouse")) {
            greenhouse(board);
        } else {
            lever(board);
        }
    }

    void greenhouse(PostingStore.Board board) throws Exception {
        List<BoardApi.ListItem> items = api.greenhouseList(board.token());
        Instant staleBefore = Instant.now().minus(props.detailRefresh());
        int kept = 0;
        int queued = 0;
        for (BoardApi.ListItem item : items) {
            if (!wanted(item.title()) && !store.savedByAnyone(board.id(), item.externalId())) {
                continue;
            }
            kept++;
            Optional<PostingStore.Existing> before = store.find(board.id(), item.externalId());
            long postingId = store.upsertPosting(board.id(), item.externalId(), item.internalJobId(), item.title(),
                    item.url(), item.updatedAt());
            boolean needDetail = before.isEmpty()
                    || !Objects.equals(before.get().sourceUpdatedAt(), item.updatedAt())
                    || before.get().lastDetailAt() == null
                    || before.get().lastDetailAt().isBefore(staleBefore);
            if (needDetail && jobs.fetchPosting(postingId)) {
                queued++;
            }
        }
        store.recordPoll(board.id(), items.size(), kept);
        log.info("greenhouse {}: {} jobs, {} kept, {} detail fetches queued", board.token(), items.size(), kept, queued);
    }

    void lever(PostingStore.Board board) throws Exception {
        List<BoardApi.Snapshot> postings = api.leverList(board.token());
        int kept = 0;
        int changed = 0;
        for (BoardApi.Snapshot p : postings) {
            if (!wanted(p.title()) && !store.savedByAnyone(board.id(), p.externalId())) {
                continue;
            }
            kept++;
            long postingId = store.upsertPosting(board.id(), p.externalId(), null, p.title(), p.url(), null);
            Optional<Long> version = store.saveVersionIfChanged(postingId, p, null, json.writeValueAsString(p.raw()));
            store.markDetail(postingId);
            if (version.isPresent()) {
                changed++;
            }
        }
        store.recordPoll(board.id(), postings.size(), kept);
        log.info("lever {}: {} postings, {} kept, {} new versions", board.token(), postings.size(), kept, changed);
    }
}

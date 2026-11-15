package io.github.itsmeck24.fineprint.queue;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
// tracked postings missing from the list get a check, which decides closed vs a passing glitch.
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
        Instant started = store.dbNow();
        int[] counts = board.ats().equals("greenhouse") ? greenhouse(board) : lever(board);
        store.recordPoll(board.id(), counts[0], counts[1]);
        // only after a listing that worked: a failed list call throws before this point
        int checks = 0;
        for (long id : store.missingFromListing(board.id(), started)) {
            checks += jobs.checkPosting(id) ? 1 : 0;
        }
        for (long id : store.closedButListed(board.id(), started)) {
            checks += jobs.checkPosting(id) ? 1 : 0;
        }
        log.info("{} {}: {} listed, {} kept, {} queued, {} checks", board.ats(), board.token(), counts[0], counts[1],
                counts[2], checks);
    }

    int[] greenhouse(PostingStore.Board board) throws Exception {
        List<BoardApi.ListItem> items = api.greenhouseList(board.token());
        Set<String> tracked = store.trackedExternalIds(board.id());
        Instant staleBefore = Instant.now().minus(props.detailRefresh());
        int kept = 0;
        int queued = 0;
        for (BoardApi.ListItem item : items) {
            if (!wanted(item.title()) && !tracked.contains(item.externalId())) {
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
        return new int[] {items.size(), kept, queued};
    }

    int[] lever(PostingStore.Board board) throws Exception {
        List<BoardApi.Snapshot> postings = api.leverList(board.token());
        Set<String> tracked = store.trackedExternalIds(board.id());
        int kept = 0;
        int changed = 0;
        for (BoardApi.Snapshot p : postings) {
            if (!wanted(p.title()) && !tracked.contains(p.externalId())) {
                continue;
            }
            kept++;
            long postingId = store.upsertPosting(board.id(), p.externalId(), null, p.title(), p.url(), null);
            if (store.saveVersionIfChanged(postingId, p, null, json.writeValueAsString(p.raw())).isPresent()) {
                changed++;
            }
            store.markDetail(postingId);
        }
        return new int[] {postings.size(), kept, changed};
    }
}

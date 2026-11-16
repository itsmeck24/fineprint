package io.github.itsmeck24.fineprint.queue;

import java.time.Duration;

import org.springframework.stereotype.Component;

// the job types and their dedup keys in one place
@Component
public class Jobs {

    public static final String POLL_BOARD = "poll_board";
    public static final String FETCH_POSTING = "fetch_posting";
    public static final String EXTRACT = "extract";
    public static final String CHECK_POSTING = "check_posting";
    public static final String DIGESTS = "digests";

    private final JobQueue queue;

    public Jobs(JobQueue queue) {
        this.queue = queue;
    }

    public boolean pollBoard(long boardId) {
        return pollBoard(boardId, JobQueue.BACKGROUND);
    }

    public boolean pollBoard(long boardId, int priority) {
        return queue.enqueue(POLL_BOARD, "{\"boardId\":" + boardId + "}", "poll:" + boardId, Duration.ZERO, priority);
    }

    public boolean fetchPosting(long postingId) {
        return fetchPosting(postingId, JobQueue.BACKGROUND);
    }

    public boolean fetchPosting(long postingId, int priority) {
        return queue.enqueue(FETCH_POSTING, "{\"postingId\":" + postingId + "}", "fetch:" + postingId, Duration.ZERO, priority);
    }

    public boolean checkPosting(long postingId) {
        return checkPosting(postingId, Duration.ZERO, "check:" + postingId);
    }

    // a follow-up check needs its own key, the current check is still running under the plain one
    public boolean checkPosting(long postingId, Duration delay, String dedupKey) {
        return queue.enqueue(CHECK_POSTING, "{\"postingId\":" + postingId + "}", dedupKey, delay);
    }

    public boolean digests() {
        return queue.enqueue(DIGESTS, "{}", "digests");
    }

    public boolean extract(long versionId) {
        return queue.enqueue(EXTRACT, "{\"versionId\":" + versionId + "}", "extract:" + versionId, Duration.ZERO,
                JobQueue.NO_NETWORK);
    }
}

package io.github.itsmeck24.fineprint.queue;

import org.springframework.stereotype.Component;

// the job types and their dedup keys in one place
@Component
public class Jobs {

    public static final String POLL_BOARD = "poll_board";
    public static final String FETCH_POSTING = "fetch_posting";
    public static final String EXTRACT = "extract";

    private final JobQueue queue;

    public Jobs(JobQueue queue) {
        this.queue = queue;
    }

    public boolean pollBoard(long boardId) {
        return queue.enqueue(POLL_BOARD, "{\"boardId\":" + boardId + "}", "poll:" + boardId);
    }

    public boolean fetchPosting(long postingId) {
        return queue.enqueue(FETCH_POSTING, "{\"postingId\":" + postingId + "}", "fetch:" + postingId);
    }

    public boolean extract(long versionId) {
        return queue.enqueue(EXTRACT, "{\"versionId\":" + versionId + "}", "extract:" + versionId);
    }
}

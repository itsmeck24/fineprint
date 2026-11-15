package io.github.itsmeck24.fineprint.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.itsmeck24.fineprint.FinePrintProperties;
import io.github.itsmeck24.fineprint.store.PostingStore;

// once an hour, queue a poll for every configured board and every board a saved posting belongs to.
// the dedup key means a slow poll is never queued twice.
@Component
@ConditionalOnProperty(name = "fineprint.collector.enabled", havingValue = "true")
public class PollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PollScheduler.class);

    private final FinePrintProperties.Collector props;
    private final PostingStore store;
    private final Jobs jobs;
    private final JdbcClient jdbc;

    public PollScheduler(FinePrintProperties props, PostingStore store, Jobs jobs, JdbcClient jdbc) {
        this.props = props.collector();
        this.store = store;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelayString = "${fineprint.collector.first-poll-after:PT10S}", fixedDelayString = "${fineprint.collector.poll-every}")
    public void tick() {
        for (String b : props.boards()) {
            String[] parts = b.split(":", 2);
            store.boardId(parts[0], parts[1]);
        }
        int queued = 0;
        for (Long boardId : jdbc.sql("""
                        select id from boards where (ats || ':' || token) in (:configured)
                           or id in (select board_id from watches)
                           or id in (select p.board_id from saved_postings s join postings p on p.id = s.posting_id)""")
                .param("configured", props.boards()).query(Long.class).list()) {
            if (jobs.pollBoard(boardId)) {
                queued++;
            }
        }
        jobs.digests();
        log.info("queued {} board polls", queued);
    }
}

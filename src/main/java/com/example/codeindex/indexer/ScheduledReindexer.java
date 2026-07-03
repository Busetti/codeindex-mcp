package com.example.codeindex.indexer;

import com.example.codeindex.config.CodeindexProperties;
import com.example.codeindex.mcp.dto.IndexStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically re-indexes every configured repo so the index tracks the source over time.
 *
 * <p>Driven by {@code codeindex.schedule.cron} (Spring cron; default "-" = disabled) and gated by
 * {@code codeindex.schedule.enabled}. Keeping the index fresh this way means a support team always
 * queries current code without any manual step. For event-driven freshness, call the {@code reindex}
 * tool from a CI post-merge hook instead of (or in addition to) this schedule.
 */
@Component
public class ScheduledReindexer {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReindexer.class);

    private final CodeindexProperties props;
    private final IndexingService indexing;

    public ScheduledReindexer(CodeindexProperties props, IndexingService indexing) {
        this.props = props;
        this.indexing = indexing;
    }

    @Scheduled(cron = "${codeindex.schedule.cron:-}")
    public void reindexAll() {
        if (!props.getSchedule().isEnabled()) {
            return;
        }
        log.info("Scheduled reindex starting for {} repo(s)", props.effectiveRepos().size());
        List<IndexStats> stats = indexing.reindexConfigured();
        stats.forEach(s -> log.info("Scheduled reindex done: {}", s));
    }
}

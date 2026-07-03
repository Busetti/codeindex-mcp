package com.example.codeindex.indexer;

import com.example.codeindex.config.CodeindexProperties;
import com.example.codeindex.mcp.dto.IndexStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** Indexes the configured default root once on startup so the MCP tools work without a manual reindex. */
@Component
public class StartupIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupIndexer.class);

    private final CodeindexProperties props;
    private final IndexingService indexing;

    public StartupIndexer(CodeindexProperties props, IndexingService indexing) {
        this.props = props;
        this.indexing = indexing;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isReindexOnStartup()) {
            return;
        }
        List<IndexStats> stats = indexing.reindexConfigured();
        stats.forEach(s -> log.info("Startup index ready: {}", s));
        if (stats.isEmpty()) {
            log.warn("No repos indexed on startup. Call the 'reindex' tool once a source root is available.");
        }
    }
}

package com.example.codeindex.web;

import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.IndexStats;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs indexing off the request thread and exposes each run's progress for polling. */
@Service
public class IndexJobService {

    private static final Logger log = LoggerFactory.getLogger(IndexJobService.class);

    private final IndexingService indexing;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-job");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, IndexJob> jobs = new ConcurrentHashMap<>();

    public IndexJobService(IndexingService indexing) {
        this.indexing = indexing;
    }

    public IndexJob start(String path, String repo, List<Path> excludedPaths) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        IndexJob job = new IndexJob(id, repo, path);
        jobs.put(id, job);
        executor.submit(() -> run(job, path, repo, excludedPaths));
        return job;
    }

    private void run(IndexJob job, String path, String repo, List<Path> excludedPaths) {
        try {
            IndexStats stats = indexing.reindex(path, repo, excludedPaths, job::update);
            job.complete(stats);
        } catch (Exception e) {
            log.warn("Index job {} failed: {}", job.getId(), e.getMessage());
            job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Re-run detection for {@code repo} with its saved rule config (reparse, no re-embed). */
    public IndexJob startReapply(String repo) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        IndexJob job = new IndexJob(id, repo, "(config reapply)");
        jobs.put(id, job);
        executor.submit(() -> {
            try {
                job.complete(indexing.reapplyConfig(repo, job::update));
            } catch (Exception e) {
                log.warn("Reapply job {} failed: {}", job.getId(), e.getMessage());
                job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        return job;
    }

    public IndexJob get(String id) {
        return jobs.get(id);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

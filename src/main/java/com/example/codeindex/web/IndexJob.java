package com.example.codeindex.web;

import com.example.codeindex.mcp.dto.IndexStats;

import java.time.Instant;

/**
 * Mutable, observable state of one asynchronous indexing run, polled by the web UI.
 * Progress percent is derived from the phase (scanning 0–40, analyzing 40–60, embedding 60–100).
 */
public class IndexJob {

    public enum Status { QUEUED, RUNNING, DONE, ERROR }

    private final String id;
    private final String repo;
    private final String path;

    private volatile Status status = Status.QUEUED;
    private volatile String phase = "queued";
    private volatile int done;
    private volatile int total;
    private volatile int percent;
    private volatile String message = "";
    private volatile IndexStats stats;
    private volatile String error;
    private final Instant createdAt = Instant.now();
    private volatile Instant finishedAt;

    public IndexJob(String id, String repo, String path) {
        this.id = id;
        this.repo = repo;
        this.path = path;
    }

    public synchronized void update(String phase, int done, int total) {
        this.status = Status.RUNNING;
        this.phase = phase;
        this.done = done;
        this.total = total;
        double frac = total <= 0 ? 1.0 : Math.min(1.0, (double) done / total);
        double p = switch (phase) {
            case "scanning" -> frac * 40;
            case "analyzing" -> 40 + frac * 20;
            case "embedding" -> 60 + frac * 40;
            default -> percent;
        };
        this.percent = (int) Math.max(percent, Math.min(99, p));
        this.message = phase + " " + done + "/" + total;
    }

    public synchronized void complete(IndexStats stats) {
        this.stats = stats;
        this.status = Status.DONE;
        this.phase = "done";
        this.percent = 100;
        this.message = "Indexed " + stats.symbols() + " symbols, " + stats.hotspots() + " hotspots";
        this.finishedAt = Instant.now();
    }

    public synchronized void fail(String error) {
        this.status = Status.ERROR;
        this.error = error;
        this.message = "Failed: " + error;
        this.finishedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getRepo() { return repo; }
    public String getPath() { return path; }
    public Status getStatus() { return status; }
    public String getPhase() { return phase; }
    public int getDone() { return done; }
    public int getTotal() { return total; }
    public int getPercent() { return percent; }
    public String getMessage() { return message; }
    public IndexStats getStats() { return stats; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
}

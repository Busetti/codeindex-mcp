package com.example.codeindex.indexer;

/** Callback for long-running indexing so a UI can render a progress bar. */
@FunctionalInterface
public interface ProgressListener {

    /** @param phase e.g. "scanning" | "analyzing" | "embedding"; done/total are within that phase. */
    void onProgress(String phase, int done, int total);

    ProgressListener NOOP = (phase, done, total) -> { };
}

package com.example.codeindex.indexer.model;

/** A pre-computed performance finding (populated in P3). */
public record HotspotFinding(
        String category,   // N_PLUS_ONE | UNPAGINATED_QUERY | EXTERNAL_CALL_IN_LOOP | ...
        String severity,   // HIGH | MEDIUM | LOW
        String symbolFqn,
        String filePath,
        int line,
        String rationale
) {
}

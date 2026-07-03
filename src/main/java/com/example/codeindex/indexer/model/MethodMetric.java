package com.example.codeindex.indexer.model;

/**
 * AST-derived quality metrics for one method, collected during parsing (where the full method AST is
 * available) and consumed in-memory by {@code HotspotDetector}. Transient — not persisted; only used
 * to derive {@link HotspotFinding}s before the repo is written.
 */
public record MethodMetric(
        String methodFqn,
        String filePath,
        int line,
        int loc,                  // physical lines the method spans
        int cyclomatic,           // cyclomatic complexity
        int maxNesting,           // deepest nesting of control-flow blocks
        int paramCount,
        boolean emptyCatch,       // has a catch block that swallows the exception
        boolean stringConcatInLoop,
        boolean hasTry,           // any try/catch present in the body
        boolean secretHit,        // heuristic: hardcoded-secret literal
        boolean sqlConcatHit      // heuristic: string concatenation into a query call
) {
}

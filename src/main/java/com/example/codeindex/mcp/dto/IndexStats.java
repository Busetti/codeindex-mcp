package com.example.codeindex.mcp.dto;

/** Summary returned by reindex. */
public record IndexStats(
        String repo,
        String root,
        int symbols,
        int endpoints,
        int callEdges,
        int dataAccess,
        int externalCalls,
        int hotspots,
        int chunks) {
}

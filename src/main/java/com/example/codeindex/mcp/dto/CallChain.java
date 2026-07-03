package com.example.codeindex.mcp.dto;

import java.util.List;

/**
 * Result of trace_call_chain. {@code rendered} is a compact indented tree (cheapest for an LLM to
 * read); {@code lines} is the same data structured for programmatic use.
 */
public record CallChain(String start, String startFqn, List<TraceLine> lines, String rendered) {

    /** One node on the downstream path. {@code tags} carry [SERVICE]/[DB]/[HTTP]/[LOOP] etc. */
    public record TraceLine(int depth, String methodFqn, boolean inLoop, List<String> tags) {
    }
}

package com.example.codeindex.mcp;

import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.CallChain;
import com.example.codeindex.trace.TraceService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** The core triage tool: reconstruct the downstream call path of an endpoint/method compactly. */
@Component
public class TraceTools {

    private final TraceService traceService;
    private final IndexingService indexing;

    public TraceTools(TraceService traceService, IndexingService indexing) {
        this.traceService = traceService;
        this.indexing = indexing;
    }

    @McpTool(name = "trace_call_chain",
            description = "Trace the downstream call path of an HTTP endpoint or method "
                    + "(controller→service→repository/component) as a compact indented tree. Each node is "
                    + "tagged with [SERVICE]/[REPOSITORY]/[DB]/[HTTP]/[@Transactional] and [IN-LOOP], so a "
                    + "slow-API bottleneck (e.g. a DB or HTTP call inside a loop = N+1) is visible at a glance "
                    + "without reading source files. Start value can be a path like '/orders', a method FQN "
                    + "'Owner#method', or a bare method name.")
    public CallChain traceCallChain(
            @McpToolParam(description = "Endpoint path, method FQN, or bare method name to start from", required = true)
            String start,
            @McpToolParam(description = "Max depth to descend (default 6)", required = false) Integer maxDepth,
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        String r = (repo == null || repo.isBlank()) ? indexing.defaultRepo() : repo;
        return traceService.trace(r, start, maxDepth == null ? 6 : maxDepth);
    }
}

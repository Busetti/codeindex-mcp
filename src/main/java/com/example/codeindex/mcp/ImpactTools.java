package com.example.codeindex.mcp;

import com.example.codeindex.impact.ImpactService;
import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.ChangeImpact;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Change-impact analysis for developers: "who calls this and what breaks if I change it?". */
@Component
public class ImpactTools {

    private final ImpactService impactService;
    private final IndexingService indexing;

    public ImpactTools(ImpactService impactService, IndexingService indexing) {
        this.impactService = impactService;
        this.indexing = indexing;
    }

    @McpTool(name = "get_change_impact",
            description = "Reverse call graph for a method: who calls it (transitively) and which HTTP "
                    + "endpoints could break if you change it. Use before refactoring or when assessing the "
                    + "blast radius of a change. Accepts a method FQN ('Owner#method') or a bare method name.")
    public ChangeImpact getChangeImpact(
            @McpToolParam(description = "Method FQN (Owner#method) or bare method name to assess", required = true)
            String symbol,
            @McpToolParam(description = "Max levels of callers to walk up (default 6)", required = false)
            Integer maxDepth,
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        String r = (repo == null || repo.isBlank()) ? indexing.defaultRepo() : repo;
        return impactService.analyze(r, symbol, maxDepth == null ? 6 : maxDepth);
    }
}

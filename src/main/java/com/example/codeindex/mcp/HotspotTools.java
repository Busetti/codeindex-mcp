package com.example.codeindex.mcp;

import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.HotspotRow;
import com.example.codeindex.store.StructuralRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** Serves the pre-computed performance findings — the core support-triage deliverable. */
@Component
public class HotspotTools {

    private final StructuralRepository repository;
    private final IndexingService indexing;

    public HotspotTools(StructuralRepository repository, IndexingService indexing) {
        this.repository = repository;
        this.indexing = indexing;
    }

    @McpTool(name = "get_hotspot_report",
            description = "Return pre-computed performance findings ranked most-severe first: N+1 loops, "
                    + "unpaginated queries, outbound calls inside loops, SELECT *, etc. Each finding has a "
                    + "category, severity, exact location (file:line) and a one-line fix. Use this first when "
                    + "triaging 'why is this slow' — it often answers the question without reading any code.")
    public List<HotspotRow> getHotspotReport(
            @McpToolParam(description = "Optional scope: substring of a package/class/method FQN to filter to, "
                    + "e.g. 'OrderService'", required = false) String scope,
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        String r = (repo == null || repo.isBlank()) ? indexing.defaultRepo() : repo;
        return repository.listHotspots(r, scope);
    }
}

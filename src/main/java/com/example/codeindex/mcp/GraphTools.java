package com.example.codeindex.mcp;

import com.example.codeindex.graph.ArchitectureService;
import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.DependencyCycles;
import com.example.codeindex.mcp.dto.GraphView;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Architecture-health tool: circular dependencies between types. */
@Component
public class GraphTools {

    private final ArchitectureService architecture;
    private final IndexingService indexing;

    public GraphTools(ArchitectureService architecture, IndexingService indexing) {
        this.architecture = architecture;
        this.indexing = indexing;
    }

    @McpTool(name = "get_dependency_cycles",
            description = "Detect circular dependencies between types (via strongly-connected components of "
                    + "the type dependency graph). Empty means the architecture is acyclic. Each cycle is the "
                    + "set of type names that mutually depend on each other — a refactoring target.")
    public DependencyCycles getDependencyCycles(
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        String r = (repo == null || repo.isBlank()) ? indexing.defaultRepo() : repo;
        GraphView g = architecture.build(r);
        return new DependencyCycles(g.cycles().size(), g.cycles());
    }
}

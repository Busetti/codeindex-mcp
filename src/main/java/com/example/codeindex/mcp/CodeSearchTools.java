package com.example.codeindex.mcp;

import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.EndpointRow;
import com.example.codeindex.mcp.dto.MethodSource;
import com.example.codeindex.mcp.dto.SymbolHit;
import com.example.codeindex.store.StructuralRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read tools for cheap code navigation. These deliberately return compact rows (FQN + signature +
 * location) or a single method body — never whole files — so an LLM spends few tokens per triage.
 */
@Component
public class CodeSearchTools {

    private final StructuralRepository repository;
    private final IndexingService indexing;

    public CodeSearchTools(StructuralRepository repository, IndexingService indexing) {
        this.repository = repository;
        this.indexing = indexing;
    }

    @McpTool(name = "search_symbols",
            description = "Find classes/methods/fields by name or fully-qualified name. Returns compact "
                    + "rows (fqn, kind, signature, file:line) WITHOUT source bodies. Use this to locate "
                    + "code before drilling in with get_method_source or trace_call_chain.")
    public List<SymbolHit> searchSymbols(
            @McpToolParam(description = "Case-insensitive substring of the symbol name or FQN", required = true)
            String query,
            @McpToolParam(description = "Optional kind filter: CLASS | INTERFACE | ENUM | METHOD | FIELD", required = false)
            String kind,
            @McpToolParam(description = "Max results (default 50)", required = false) Integer limit,
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        int max = (limit == null || limit <= 0) ? 50 : Math.min(limit, 200);
        return repository.searchSymbols(repoOrDefault(repo), query, kind, max);
    }

    @McpTool(name = "get_endpoint_map",
            description = "List HTTP endpoints (method, path, handler FQN) discovered from "
                    + "@RestController/@RequestMapping. The natural starting point when triaging a slow API: "
                    + "find the endpoint, then trace_call_chain from its handler.")
    public List<EndpointRow> getEndpointMap(
            @McpToolParam(description = "Optional path substring filter, e.g. '/orders'", required = false)
            String pathPattern,
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        return repository.listEndpoints(repoOrDefault(repo), pathPattern);
    }

    @McpTool(name = "get_method_source",
            description = "Return the source of a SINGLE method (not the whole file). Accepts an exact FQN "
                    + "like 'com.example.orders.service.OrderService#listAllOrders' or a bare method name.")
    public MethodSource getMethodSource(
            @McpToolParam(description = "Method FQN (Owner#method) or a bare method name", required = true)
            String fqn,
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        return repository.methodSource(repoOrDefault(repo), fqn)
                .orElseGet(() -> new MethodSource(fqn, "-", "// no indexed method found for: " + fqn));
    }

    private String repoOrDefault(String repo) {
        return (repo == null || repo.isBlank()) ? indexing.defaultRepo() : repo;
    }
}

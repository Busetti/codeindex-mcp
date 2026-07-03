package com.example.codeindex.mcp;

import com.example.codeindex.indexer.ChunkEmbedder;
import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.SemanticHit;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** Semantic ("find code about X") search over embedded method chunks — the fuzzy-discovery fallback. */
@Component
public class SemanticTools {

    private final ChunkEmbedder chunkEmbedder;
    private final IndexingService indexing;

    public SemanticTools(ChunkEmbedder chunkEmbedder, IndexingService indexing) {
        this.chunkEmbedder = chunkEmbedder;
        this.indexing = indexing;
    }

    @McpTool(name = "semantic_search",
            description = "Find methods semantically related to a natural-language query (e.g. 'retry payment', "
                    + "'where are ratings fetched') when you don't know the class or method names. Returns the "
                    + "matching method FQN, file:line, and a short snippet — never whole files. For exact "
                    + "navigation prefer search_symbols/trace_call_chain; use this for discovery.")
    public List<SemanticHit> semanticSearch(
            @McpToolParam(description = "Natural-language description of the code you're looking for", required = true)
            String query,
            @McpToolParam(description = "Number of results to return (default 5)", required = false) Integer topK,
            @McpToolParam(description = "Repo name; defaults to the configured repo", required = false) String repo) {
        String r = (repo == null || repo.isBlank()) ? indexing.defaultRepo() : repo;
        return chunkEmbedder.search(r, query, topK == null ? 5 : topK);
    }
}

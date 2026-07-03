package com.example.codeindex.mcp;

import com.example.codeindex.indexer.IndexingService;
import com.example.codeindex.mcp.dto.IndexStats;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Admin tool to (re)build the index for a source tree. */
@Component
public class IndexAdminTools {

    private final IndexingService indexing;

    public IndexAdminTools(IndexingService indexing) {
        this.indexing = indexing;
    }

    @McpTool(name = "reindex",
            description = "Parse a Java/Spring source tree and rebuild its structural index (symbols, "
                    + "endpoints, call graph, data access, external calls, hotspots). Returns counts. "
                    + "Call this once before using the other tools, or after the code changes.")
    public IndexStats reindex(
            @McpToolParam(description = "Absolute or relative path to the source root; "
                    + "defaults to the configured codeindex.default-root", required = false) String path,
            @McpToolParam(description = "Logical repo name to namespace the index; "
                    + "defaults to codeindex.default-repo", required = false) String repo) {
        return indexing.reindex(path, repo);
    }
}

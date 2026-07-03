package com.example.codeindex.mcp.dto;

/** One indexed codebase with its headline counts. Returned by list_repos / GET /api/repos. */
public record RepoSummary(String repo, int symbols, int endpoints, int hotspots, int chunks) {
}

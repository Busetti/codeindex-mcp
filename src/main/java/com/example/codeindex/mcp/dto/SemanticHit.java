package com.example.codeindex.mcp.dto;

/** One semantic_search result: a symbol plus a short snippet (never a whole file). */
public record SemanticHit(String fqn, String kind, String location, Double score, String snippet) {
}

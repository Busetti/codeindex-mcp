package com.example.codeindex.mcp.dto;

/** Compact symbol match returned by search_symbols (no source body — keeps tokens low). */
public record SymbolHit(String fqn, String kind, String signature, String location) {
}

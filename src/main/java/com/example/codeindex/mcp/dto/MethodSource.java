package com.example.codeindex.mcp.dto;

/** The source of a single method (only) returned by get_method_source. */
public record MethodSource(String fqn, String location, String source) {
}

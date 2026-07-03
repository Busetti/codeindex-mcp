package com.example.codeindex.mcp.dto;

import java.util.List;

/** Circular type dependencies (each cycle is a list of simple type names). */
public record DependencyCycles(int count, List<List<String>> cycles) {
}

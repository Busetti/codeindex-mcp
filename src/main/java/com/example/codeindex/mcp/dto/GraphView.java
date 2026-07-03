package com.example.codeindex.mcp.dto;

import java.util.List;

/** Type-level dependency graph for the architecture view. */
public record GraphView(List<Node> nodes, List<Edge> edges, List<List<String>> cycles) {

    /** A type node. {@code id} is the FQN; {@code label} the simple name. */
    public record Node(String id, String label, String stereotype) {
    }

    /** A directed dependency {@code source}→{@code target} with the number of underlying calls. */
    public record Edge(String source, String target, int count) {
    }
}

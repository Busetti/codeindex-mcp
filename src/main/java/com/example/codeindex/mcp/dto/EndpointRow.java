package com.example.codeindex.mcp.dto;

/** One row of the endpoint map returned by get_endpoint_map. */
public record EndpointRow(String httpMethod, String path, String handlerFqn) {
}

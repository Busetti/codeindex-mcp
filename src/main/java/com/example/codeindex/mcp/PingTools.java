package com.example.codeindex.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Minimal P0 smoke-test tool used to prove the Streamable HTTP MCP transport works
 * before the real code-intelligence tools are wired up. Safe to keep as a health probe.
 */
@Component
public class PingTools {

    @McpTool(name = "ping", description = "Health check for the code-intelligence MCP server. Returns 'pong: <message>'.")
    public String ping(
            @McpToolParam(description = "Any text to echo back", required = false) String message) {
        return "pong: " + (message == null ? "" : message);
    }
}

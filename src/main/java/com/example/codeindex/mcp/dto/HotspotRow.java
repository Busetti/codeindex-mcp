package com.example.codeindex.mcp.dto;

/** One performance finding returned by get_hotspot_report, ordered most-severe first. */
public record HotspotRow(String category, String severity, String symbolFqn, String location, String rationale) {
}

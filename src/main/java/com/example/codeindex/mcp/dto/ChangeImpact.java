package com.example.codeindex.mcp.dto;

import java.util.List;

/**
 * Result of get_change_impact — the reverse-call-graph answer to "what breaks if I change this?".
 * {@code rendered} is a compact upward tree; {@code impactedEndpoints} are the HTTP entry points
 * whose behaviour could change.
 */
public record ChangeImpact(
        String target,
        String targetFqn,
        List<String> directCallers,
        List<String> impactedEndpoints,
        int totalCallers,
        String rendered) {
}

package com.example.codeindex.rules;

import java.util.Set;

/**
 * Per-repo, configurable thresholds and enablement for the {@code HotspotDetector} rules. Lets each
 * codebase tune what counts as "too long / too complex" and switch noisy categories off, instead of
 * baking the numbers into the detector. Persisted by {@code RuleConfigStore}; edited from the UI.
 *
 * <p>{@code disabledRules} holds hotspot category names (e.g. {@code "TOO_MANY_PARAMS"}) that should
 * not be emitted for this repo.
 */
public record RuleConfig(
        int godClassLoc,
        int godClassMethods,
        int longMethodLoc,
        int highComplexity,
        int deepNesting,
        int tooManyParams,
        Set<String> disabledRules) {

    /** The historical hardcoded defaults — used when a repo has no saved config. */
    public static RuleConfig defaults() {
        return new RuleConfig(500, 20, 80, 10, 4, 5, Set.of());
    }

    /** Never-null accessor so callers don't NPE on a config deserialized without the field. */
    public Set<String> disabledRulesOrEmpty() {
        return disabledRules == null ? Set.of() : disabledRules;
    }

    /** Whether a given hotspot category should be emitted for this repo. */
    public boolean enabled(String category) {
        return !disabledRulesOrEmpty().contains(category);
    }
}

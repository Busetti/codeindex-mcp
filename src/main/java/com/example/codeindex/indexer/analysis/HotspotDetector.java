package com.example.codeindex.indexer.analysis;

import com.example.codeindex.indexer.model.CallEdge;
import com.example.codeindex.indexer.model.DataAccessFact;
import com.example.codeindex.indexer.model.ExternalCallFact;
import com.example.codeindex.indexer.model.HotspotFinding;
import com.example.codeindex.indexer.model.MethodMetric;
import com.example.codeindex.indexer.model.ScanResult;
import com.example.codeindex.indexer.model.Symbol;
import com.example.codeindex.rules.RuleConfig;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Derives performance findings from the facts already collected in a {@link ScanResult}. Pure
 * heuristics over structure — no code execution, no LLM — so it is cheap and deterministic. This is
 * the payload that lets a support team (or an LLM) triage a slow API without reading source.
 *
 * <p>Thresholds and per-rule enablement come from a {@link RuleConfig} (configurable per repo);
 * the {@code detect(ScanResult)} overload uses {@link RuleConfig#defaults()}.
 */
@Component
public class HotspotDetector {

    private static final String HEURISTIC = "(heuristic — verify) ";
    public static final String HARDCODED_SECRET_RATIONALE = HEURISTIC
            + "Value looks like a hardcoded credential/secret. Move it to a secrets manager or "
            + "environment/config, and rotate it if it was ever committed.";

    public void detect(ScanResult r) {
        detect(r, RuleConfig.defaults());
    }

    public void detect(ScanResult r, RuleConfig cfg) {
        Map<String, Symbol> methodByFqn = new HashMap<>();
        for (Symbol s : r.symbols()) {
            if ("METHOD".equals(s.kind())) {
                methodByFqn.put(s.fqn(), s);
            }
        }

        for (DataAccessFact f : r.dataAccess()) {
            Symbol owner = methodByFqn.get(f.ownerFqn());
            String file = owner == null ? "" : owner.filePath();

            if (cfg.enabled("N_PLUS_ONE") && "REPOSITORY_METHOD".equals(f.kind()) && f.inLoop()) {
                r.hotspots().add(new HotspotFinding("N_PLUS_ONE", "HIGH", f.ownerFqn(), file, f.line(),
                        "Repository call '" + f.detail() + "' runs inside a loop (classic N+1). "
                                + "Fetch in one query (join/IN clause) or a batch before the loop."));
            }
            if (cfg.enabled("UNPAGINATED_QUERY") && f.detail() != null
                    && f.detail().toLowerCase().endsWith("findall")) {
                r.hotspots().add(new HotspotFinding("UNPAGINATED_QUERY", "MEDIUM", f.ownerFqn(), file, f.line(),
                        "Unbounded '" + f.detail() + "' with no Pageable; result set grows with the table. "
                                + "Add pagination or a filter."));
            }
            if (cfg.enabled("NATIVE_SELECT_STAR") && "NATIVE_QUERY".equals(f.kind()) && f.detail() != null
                    && f.detail().toLowerCase().contains("select *")) {
                r.hotspots().add(new HotspotFinding("NATIVE_SELECT_STAR", "LOW", f.ownerFqn(), file, f.line(),
                        "Native query selects all columns (SELECT *); project only the columns needed."));
            }
        }

        // Methods that themselves perform an outbound HTTP call.
        Set<String> httpOwners = new HashSet<>();
        for (ExternalCallFact f : r.externalCalls()) {
            httpOwners.add(f.ownerFqn());
            if (cfg.enabled("EXTERNAL_CALL_IN_LOOP") && f.inLoop()) {
                Symbol owner = methodByFqn.get(f.ownerFqn());
                String file = owner == null ? "" : owner.filePath();
                r.hotspots().add(new HotspotFinding("EXTERNAL_CALL_IN_LOOP", "HIGH", f.ownerFqn(), file, f.line(),
                        "Outbound call '" + f.detail() + "' runs inside a loop; total latency scales with "
                                + "row count. Batch the request or call it concurrently/outside the loop."));
            }
        }

        // Transitive case: a loop that calls a method which itself does HTTP (the real per-row remote cost).
        if (cfg.enabled("EXTERNAL_CALL_IN_LOOP")) {
            for (CallEdge e : r.callEdges()) {
                if (e.inLoop() && e.calleeFqn() != null && httpOwners.contains(e.calleeFqn())) {
                    Symbol caller = methodByFqn.get(e.callerFqn());
                    String file = caller == null ? "" : caller.filePath();
                    r.hotspots().add(new HotspotFinding("EXTERNAL_CALL_IN_LOOP", "HIGH", e.callerFqn(), file, e.line(),
                            "Calls '" + shortName(e.calleeFqn()) + "' inside a loop, and that method makes an "
                                    + "outbound HTTP call — remote latency is paid once per iteration. "
                                    + "Batch or parallelize."));
                }
            }
        }

        detectMethodQuality(r, httpOwners, cfg);
        detectGodClasses(r, cfg);
    }

    /** Per-method maintainability, error-handling and security rules over the collected metrics. */
    private void detectMethodQuality(ScanResult r, Set<String> httpOwners, RuleConfig cfg) {
        for (MethodMetric m : r.metrics()) {
            if (cfg.enabled("LONG_METHOD") && m.loc() > cfg.longMethodLoc()) {
                r.hotspots().add(new HotspotFinding("LONG_METHOD", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        "Method spans " + m.loc() + " lines (> " + cfg.longMethodLoc() + "); extract smaller, "
                                + "single-purpose methods to make it testable and readable."));
            }
            if (cfg.enabled("HIGH_COMPLEXITY") && m.cyclomatic() > cfg.highComplexity()) {
                r.hotspots().add(new HotspotFinding("HIGH_COMPLEXITY", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        "Cyclomatic complexity " + m.cyclomatic() + " (> " + cfg.highComplexity() + "); too many branches. "
                                + "Split the method or replace conditionals with polymorphism/early returns."));
            }
            if (cfg.enabled("DEEP_NESTING") && m.maxNesting() > cfg.deepNesting()) {
                r.hotspots().add(new HotspotFinding("DEEP_NESTING", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        "Control flow nests " + m.maxNesting() + " levels deep (> " + cfg.deepNesting() + "); "
                                + "flatten with guard clauses / early returns or extract inner blocks."));
            }
            if (cfg.enabled("TOO_MANY_PARAMS") && m.paramCount() > cfg.tooManyParams()) {
                r.hotspots().add(new HotspotFinding("TOO_MANY_PARAMS", "LOW", m.methodFqn(), m.filePath(), m.line(),
                        m.paramCount() + " parameters (> " + cfg.tooManyParams() + "); group related args into a "
                                + "parameter object / DTO."));
            }
            if (cfg.enabled("SILENT_FAILURE") && m.emptyCatch()) {
                r.hotspots().add(new HotspotFinding("SILENT_FAILURE", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        "Empty catch block swallows the exception; at minimum log it, ideally handle or "
                                + "rethrow so failures are visible."));
            }
            if (cfg.enabled("STRING_CONCAT_IN_LOOP") && m.stringConcatInLoop()) {
                r.hotspots().add(new HotspotFinding("STRING_CONCAT_IN_LOOP", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        "String concatenation inside a loop creates O(n²) garbage; use a StringBuilder "
                                + "(or Collectors.joining) instead."));
            }
            if (cfg.enabled("HARDCODED_SECRET") && m.secretHit()) {
                r.hotspots().add(new HotspotFinding("HARDCODED_SECRET", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        HARDCODED_SECRET_RATIONALE));
            }
            if (cfg.enabled("SQL_INJECTION_RISK") && m.sqlConcatHit()) {
                r.hotspots().add(new HotspotFinding("SQL_INJECTION_RISK", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        HEURISTIC + "SQL built via string concatenation is injection-prone; use parameterized "
                                + "queries / bind variables instead of concatenating input."));
            }
            if (cfg.enabled("MISSING_ERROR_HANDLING") && httpOwners.contains(m.methodFqn()) && !m.hasTry()) {
                r.hotspots().add(new HotspotFinding("MISSING_ERROR_HANDLING", "MEDIUM", m.methodFqn(), m.filePath(), m.line(),
                        "Makes an outbound call with no try/catch; a remote failure/timeout propagates "
                                + "unhandled. Add error handling (and a timeout/fallback)."));
            }
        }
    }

    /** Class-level "God class" rule: aggregate method count and line span per owning type. */
    private void detectGodClasses(ScanResult r, RuleConfig cfg) {
        if (!cfg.enabled("GOD_CLASS")) {
            return;
        }
        Map<String, Integer> methodCount = new HashMap<>();
        for (Symbol s : r.symbols()) {
            if ("METHOD".equals(s.kind()) && s.ownerFqn() != null) {
                methodCount.merge(s.ownerFqn(), 1, Integer::sum);
            }
        }
        for (Symbol s : r.symbols()) {
            if (!"CLASS".equals(s.kind())) {
                continue;
            }
            int loc = Math.max(0, s.endLine() - s.startLine());
            int methods = methodCount.getOrDefault(s.fqn(), 0);
            if (loc > cfg.godClassLoc() || methods > cfg.godClassMethods()) {
                r.hotspots().add(new HotspotFinding("GOD_CLASS", "MEDIUM", s.fqn(), s.filePath(), s.startLine(),
                        "Large class (" + loc + " lines, " + methods + " methods) likely holds too many "
                                + "responsibilities; split by responsibility (SRP) into cohesive classes."));
            }
        }
    }

    private String shortName(String fqn) {
        int hash = fqn.indexOf('#');
        if (hash < 0) {
            return fqn;
        }
        String owner = fqn.substring(0, hash);
        int dot = owner.lastIndexOf('.');
        return (dot < 0 ? owner : owner.substring(dot + 1)) + fqn.substring(hash);
    }
}

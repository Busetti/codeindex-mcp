package com.example.codeindex.indexer;

import com.example.codeindex.indexer.analysis.HotspotDetector;
import com.example.codeindex.indexer.model.HotspotFinding;
import com.example.codeindex.indexer.model.ScanResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast, DB-free verification of the parser + heuristics against the sample-target corpus.
 * Asserts the intentional perf smells are detected deterministically.
 */
class SampleTargetIndexTest {

    private static ScanResult result;

    @BeforeAll
    static void indexSampleTarget() {
        ScanResult r = new JavaCodeIndexer().scan(Path.of("examples/sample-target"));
        new HotspotDetector().detect(r);
        result = r;
    }

    @Test
    void discoversEndpoints() {
        Set<String> paths = result.endpoints().stream()
                .map(e -> e.httpMethod() + " " + e.path())
                .collect(Collectors.toSet());
        assertThat(paths).contains("GET /orders", "GET /orders/{id}");
    }

    @Test
    void resolvesCallChainAndFlagsLoopCall() {
        boolean findByIdInLoop = result.callEdges().stream().anyMatch(e ->
                "com.example.orders.repo.CustomerRepository#findById".equals(e.calleeFqn()) && e.inLoop());
        assertThat(findByIdInLoop).as("customerRepository.findById is called inside the loop").isTrue();
    }

    @Test
    void detectsPerformanceHotspots() {
        Set<String> categories = result.hotspots().stream()
                .map(HotspotFinding::category)
                .collect(Collectors.toSet());
        assertThat(categories).contains(
                "N_PLUS_ONE",              // customerRepository.findById in loop
                "UNPAGINATED_QUERY",       // orderRepository.findAll
                "EXTERNAL_CALL_IN_LOOP",   // enrichmentClient.fetchRating in loop
                "NATIVE_SELECT_STAR");     // @Query native SELECT *
    }

    @Test
    void nPlusOneIsAttributedToTheServiceMethod() {
        boolean attributed = result.hotspots().stream().anyMatch(h ->
                h.category().equals("N_PLUS_ONE")
                        && h.symbolFqn().equals("com.example.orders.service.OrderService#listAllOrders"));
        assertThat(attributed).isTrue();
    }

    @Test
    void detectsMaintainabilityAndSecuritySmells() {
        Set<String> categories = result.hotspots().stream()
                .map(HotspotFinding::category)
                .collect(Collectors.toSet());
        assertThat(categories).contains(
                "GOD_CLASS",              // LegacyReportService has >20 methods
                "LONG_METHOD",            // generateReport is long
                "HIGH_COMPLEXITY",        // generateReport CC > 10
                "DEEP_NESTING",           // 5 levels of nesting
                "TOO_MANY_PARAMS",        // generateReport has 6 params
                "SILENT_FAILURE",         // empty catch block
                "STRING_CONCAT_IN_LOOP",  // report = report + a in a loop
                "HARDCODED_SECRET",       // API_KEY field + password local
                "SQL_INJECTION_RISK",     // executeQuery("... " + userId)
                "MISSING_ERROR_HANDLING"); // EnrichmentClient.fetchRating: HTTP call, no try
    }

    @Test
    void heuristicFindingsAreLabelled() {
        boolean labelled = result.hotspots().stream()
                .filter(h -> h.category().equals("HARDCODED_SECRET") || h.category().equals("SQL_INJECTION_RISK"))
                .allMatch(h -> h.rationale().startsWith("(heuristic"));
        assertThat(labelled).as("security heuristics are flagged as such").isTrue();
    }

    @Test
    void godClassIsAttributedToLegacyService() {
        boolean attributed = result.hotspots().stream().anyMatch(h ->
                h.category().equals("GOD_CLASS")
                        && h.symbolFqn().equals("com.example.orders.legacy.LegacyReportService"));
        assertThat(attributed).isTrue();
    }
}

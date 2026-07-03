package com.example.codeindex.store;

import com.example.codeindex.indexer.model.CallEdge;
import com.example.codeindex.indexer.model.DataAccessFact;
import com.example.codeindex.indexer.model.EndpointInfo;
import com.example.codeindex.indexer.model.ExternalCallFact;
import com.example.codeindex.indexer.model.HotspotFinding;
import com.example.codeindex.indexer.model.ScanResult;
import com.example.codeindex.indexer.model.Symbol;
import com.example.codeindex.mcp.dto.EndpointRow;
import com.example.codeindex.mcp.dto.HotspotRow;
import com.example.codeindex.mcp.dto.MethodSource;
import com.example.codeindex.mcp.dto.RepoSummary;
import com.example.codeindex.mcp.dto.SymbolHit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads/writes the structural index tables. Persistence for a repo is replace-all inside one
 * transaction so an in-flight reindex never exposes a partial graph.
 */
@Repository
public class StructuralRepository {

    private final JdbcTemplate jdbc;

    public StructuralRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void replaceRepo(String repo, ScanResult r) {
        clear(repo);
        insertSymbols(repo, r.symbols());
        insertEndpoints(repo, r.endpoints());
        insertCallEdges(repo, r.callEdges());
        insertDataAccess(repo, r.dataAccess());
        insertExternalCalls(repo, r.externalCalls());
        insertHotspots(repo, r.hotspots());
    }

    private void clear(String repo) {
        for (String t : List.of("symbols", "endpoints", "call_edges",
                "data_access", "external_calls", "hotspots")) {
            jdbc.update("DELETE FROM " + t + " WHERE repo = ?", repo);
        }
    }

    private void insertSymbols(String repo, List<Symbol> symbols) {
        jdbc.batchUpdate("""
                INSERT INTO symbols
                  (repo, kind, fqn, name, signature, owner_fqn, stereotype, annotations,
                   file_path, start_line, end_line, source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                symbols, 200, (ps, s) -> {
                    ps.setString(1, repo);
                    ps.setString(2, s.kind());
                    ps.setString(3, s.fqn());
                    ps.setString(4, s.name());
                    ps.setString(5, s.signature());
                    ps.setString(6, s.ownerFqn());
                    ps.setString(7, s.stereotype());
                    ps.setString(8, s.annotations());
                    ps.setString(9, s.filePath());
                    ps.setInt(10, s.startLine());
                    ps.setInt(11, s.endLine());
                    ps.setString(12, s.source());
                });
    }

    private void insertEndpoints(String repo, List<EndpointInfo> endpoints) {
        jdbc.batchUpdate("""
                INSERT INTO endpoints (repo, http_method, path, handler_fqn, controller_fqn)
                VALUES (?,?,?,?,?)""",
                endpoints, 200, (ps, e) -> {
                    ps.setString(1, repo);
                    ps.setString(2, e.httpMethod());
                    ps.setString(3, e.path());
                    ps.setString(4, e.handlerFqn());
                    ps.setString(5, e.controllerFqn());
                });
    }

    private void insertCallEdges(String repo, List<CallEdge> edges) {
        jdbc.batchUpdate("""
                INSERT INTO call_edges (repo, caller_fqn, callee_fqn, callee_simple, line, in_loop)
                VALUES (?,?,?,?,?,?)""",
                edges, 500, (ps, e) -> {
                    ps.setString(1, repo);
                    ps.setString(2, e.callerFqn());
                    ps.setString(3, e.calleeFqn());
                    ps.setString(4, e.calleeSimple());
                    ps.setInt(5, e.line());
                    ps.setBoolean(6, e.inLoop());
                });
    }

    private void insertDataAccess(String repo, List<DataAccessFact> facts) {
        jdbc.batchUpdate("""
                INSERT INTO data_access (repo, owner_fqn, kind, detail, line, in_loop)
                VALUES (?,?,?,?,?,?)""",
                facts, 200, (ps, f) -> {
                    ps.setString(1, repo);
                    ps.setString(2, f.ownerFqn());
                    ps.setString(3, f.kind());
                    ps.setString(4, f.detail());
                    ps.setInt(5, f.line());
                    ps.setBoolean(6, f.inLoop());
                });
    }

    private void insertExternalCalls(String repo, List<ExternalCallFact> facts) {
        jdbc.batchUpdate("""
                INSERT INTO external_calls (repo, owner_fqn, kind, detail, line, in_loop)
                VALUES (?,?,?,?,?,?)""",
                facts, 200, (ps, f) -> {
                    ps.setString(1, repo);
                    ps.setString(2, f.ownerFqn());
                    ps.setString(3, f.kind());
                    ps.setString(4, f.detail());
                    ps.setInt(5, f.line());
                    ps.setBoolean(6, f.inLoop());
                });
    }

    private void insertHotspots(String repo, List<HotspotFinding> findings) {
        jdbc.batchUpdate("""
                INSERT INTO hotspots (repo, category, severity, symbol_fqn, file_path, line, rationale)
                VALUES (?,?,?,?,?,?,?)""",
                findings, 200, (ps, h) -> {
                    ps.setString(1, repo);
                    ps.setString(2, h.category());
                    ps.setString(3, h.severity());
                    ps.setString(4, h.symbolFqn());
                    ps.setString(5, h.filePath());
                    ps.setInt(6, h.line());
                    ps.setString(7, h.rationale());
                });
    }

    // ---- queries used by the MCP tools ------------------------------------

    public int countSymbols(String repo) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM symbols WHERE repo = ?", Integer.class, repo);
        return n == null ? 0 : n;
    }

    /** All indexed repos with headline counts (structural tables + pgvector chunks). */
    public List<RepoSummary> listRepos() {
        Map<String, Integer> sym = groupCount("SELECT repo, count(*) FROM symbols GROUP BY repo");
        Map<String, Integer> eps = groupCount("SELECT repo, count(*) FROM endpoints GROUP BY repo");
        Map<String, Integer> hot = groupCount("SELECT repo, count(*) FROM hotspots GROUP BY repo");
        Map<String, Integer> chunks = groupCount(
                "SELECT metadata->>'repo' AS repo, count(*) FROM code_chunks GROUP BY metadata->>'repo'");

        TreeSet<String> repos = new TreeSet<>();
        for (Map<String, Integer> m : List.of(sym, eps, hot, chunks)) {
            m.keySet().forEach(k -> { if (k != null) repos.add(k); });
        }

        List<RepoSummary> out = new ArrayList<>();
        for (String r : repos) {
            out.add(new RepoSummary(r, sym.getOrDefault(r, 0), eps.getOrDefault(r, 0),
                    hot.getOrDefault(r, 0), chunks.getOrDefault(r, 0)));
        }
        return out;
    }

    private Map<String, Integer> groupCount(String sql) {
        Map<String, Integer> m = new HashMap<>();
        jdbc.query(sql, rs -> { m.put(rs.getString(1), rs.getInt(2)); });
        return m;
    }

    private static final RowMapper<SymbolHit> SYMBOL_HIT = (rs, i) -> new SymbolHit(
            rs.getString("fqn"),
            rs.getString("kind"),
            rs.getString("signature"),
            rs.getString("file_path") + ":" + rs.getInt("start_line"));

    /** Name/FQN substring match, optionally filtered by kind. Never returns source bodies. */
    public List<SymbolHit> searchSymbols(String repo, String query, String kind, int limit) {
        String like = "%" + (query == null ? "" : query.toLowerCase()) + "%";
        StringBuilder sql = new StringBuilder("""
                SELECT fqn, kind, signature, file_path, start_line
                FROM symbols
                WHERE repo = ? AND (lower(name) LIKE ? OR lower(fqn) LIKE ?)""");
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = upper(?)");
        }
        sql.append(" ORDER BY (kind = 'METHOD') DESC, fqn LIMIT ?");
        Object[] args = (kind != null && !kind.isBlank())
                ? new Object[]{repo, like, like, kind.trim(), limit}
                : new Object[]{repo, like, like, limit};
        return jdbc.query(sql.toString(), SYMBOL_HIT, args);
    }

    /** Endpoint map, optionally filtered by a path substring. */
    public List<EndpointRow> listEndpoints(String repo, String pathPattern) {
        if (pathPattern == null || pathPattern.isBlank()) {
            return jdbc.query("""
                    SELECT http_method, path, handler_fqn FROM endpoints
                    WHERE repo = ? ORDER BY path, http_method""",
                    (rs, i) -> new EndpointRow(rs.getString(1), rs.getString(2), rs.getString(3)), repo);
        }
        return jdbc.query("""
                SELECT http_method, path, handler_fqn FROM endpoints
                WHERE repo = ? AND lower(path) LIKE ? ORDER BY path, http_method""",
                (rs, i) -> new EndpointRow(rs.getString(1), rs.getString(2), rs.getString(3)),
                repo, "%" + pathPattern.toLowerCase() + "%");
    }

    /**
     * Resolve a single method's source. Accepts an exact FQN ({@code Owner#method}) or a bare/simple
     * name; on ambiguity the first match by FQN order is returned.
     */
    public Optional<MethodSource> methodSource(String repo, String fqnOrName) {
        String needle = fqnOrName == null ? "" : fqnOrName.trim();
        List<MethodSource> hits = jdbc.query("""
                SELECT fqn, file_path, start_line, source
                FROM symbols
                WHERE repo = ? AND kind = 'METHOD'
                  AND (fqn = ? OR name = ? OR fqn LIKE ?)
                ORDER BY (fqn = ?) DESC, fqn
                LIMIT 1""",
                (rs, i) -> new MethodSource(
                        rs.getString("fqn"),
                        rs.getString("file_path") + ":" + rs.getInt("start_line"),
                        rs.getString("source")),
                repo, needle, needle, "%#" + needle, needle);
        return hits.stream().findFirst();
    }

    /** Findings ordered HIGH→LOW, optionally filtered by a scope substring over the symbol FQN. */
    public List<HotspotRow> listHotspots(String repo, String scope) {
        String order = " ORDER BY CASE severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, symbol_fqn";
        if (scope == null || scope.isBlank()) {
            return jdbc.query("SELECT category, severity, symbol_fqn, file_path, line, rationale "
                            + "FROM hotspots WHERE repo = ?" + order,
                    HOTSPOT_ROW, repo);
        }
        return jdbc.query("SELECT category, severity, symbol_fqn, file_path, line, rationale "
                        + "FROM hotspots WHERE repo = ? AND lower(symbol_fqn) LIKE ?" + order,
                HOTSPOT_ROW, repo, "%" + scope.toLowerCase() + "%");
    }

    private static final RowMapper<HotspotRow> HOTSPOT_ROW = (rs, i) -> new HotspotRow(
            rs.getString("category"), rs.getString("severity"), rs.getString("symbol_fqn"),
            rs.getString("file_path") + ":" + rs.getInt("line"), rs.getString("rationale"));

    // ---- bulk loaders for trace_call_chain / hotspots ---------------------

    /** Metadata for one method symbol. */
    public record MethodMeta(String stereotype, String annotations, String location) {
    }

    /** One resolved outgoing call. */
    public record Edge(String calleeFqn, boolean inLoop) {
    }

    public Map<String, MethodMeta> loadMethodMeta(String repo) {
        Map<String, MethodMeta> meta = new HashMap<>();
        jdbc.query("""
                SELECT fqn, stereotype, annotations, file_path, start_line
                FROM symbols WHERE repo = ? AND kind = 'METHOD'""",
                rs -> {
                    meta.put(rs.getString("fqn"), new MethodMeta(
                            rs.getString("stereotype"), rs.getString("annotations"),
                            rs.getString("file_path") + ":" + rs.getInt("start_line")));
                }, repo);
        return meta;
    }

    /** caller FQN -> resolved outgoing edges (unresolved callees are skipped). */
    public Map<String, List<Edge>> loadCallGraph(String repo) {
        Map<String, List<Edge>> graph = new HashMap<>();
        jdbc.query("""
                SELECT caller_fqn, callee_fqn, in_loop FROM call_edges
                WHERE repo = ? AND callee_fqn IS NOT NULL ORDER BY line""",
                rs -> {
                    graph.computeIfAbsent(rs.getString("caller_fqn"), k -> new ArrayList<>())
                         .add(new Edge(rs.getString("callee_fqn"), rs.getBoolean("in_loop")));
                }, repo);
        return graph;
    }

    /** Type FQN -> stereotype (nullable) for CLASS/INTERFACE/ENUM symbols; drives the dependency graph. */
    public Map<String, String> loadTypeStereotypes(String repo) {
        Map<String, String> types = new HashMap<>();
        jdbc.query("""
                SELECT fqn, stereotype FROM symbols
                WHERE repo = ? AND kind IN ('CLASS','INTERFACE','ENUM')""",
                rs -> { types.put(rs.getString("fqn"), rs.getString("stereotype")); }, repo);
        return types;
    }

    /** callee FQN -> distinct caller FQNs (reverse call graph, for change-impact). */
    public Map<String, List<String>> loadReverseCallGraph(String repo) {
        Map<String, java.util.LinkedHashSet<String>> rev = new HashMap<>();
        jdbc.query("""
                SELECT caller_fqn, callee_fqn FROM call_edges
                WHERE repo = ? AND callee_fqn IS NOT NULL""",
                rs -> {
                    rev.computeIfAbsent(rs.getString("callee_fqn"), k -> new java.util.LinkedHashSet<>())
                       .add(rs.getString("caller_fqn"));
                }, repo);
        Map<String, List<String>> out = new HashMap<>();
        rev.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    /** handler method FQN -> its HTTP endpoint labels ("GET /orders"), for impact resolution. */
    public Map<String, List<String>> endpointsByHandler(String repo) {
        Map<String, List<String>> m = new HashMap<>();
        jdbc.query("SELECT handler_fqn, http_method, path FROM endpoints WHERE repo = ?",
                rs -> {
                    m.computeIfAbsent(rs.getString("handler_fqn"), k -> new ArrayList<>())
                     .add(rs.getString("http_method") + " " + rs.getString("path"));
                }, repo);
        return m;
    }

    public Set<String> ownersWith(String repo, String table) {
        Set<String> owners = new HashSet<>();
        jdbc.query("SELECT DISTINCT owner_fqn FROM " + table + " WHERE repo = ?",
                rs -> { owners.add(rs.getString(1)); }, repo);
        return owners;
    }

    /**
     * Resolve a trace start to a method FQN. Accepts an HTTP path ({@code /orders}), an exact method
     * FQN ({@code Owner#method}), or a bare method name.
     */
    public Optional<String> resolveStart(String repo, String input) {
        String in = input == null ? "" : input.trim();
        if (in.startsWith("/")) {
            List<String> handlers = jdbc.query("""
                    SELECT handler_fqn FROM endpoints WHERE repo = ? AND path = ?
                    ORDER BY http_method LIMIT 1""",
                    (rs, i) -> rs.getString(1), repo, in);
            if (!handlers.isEmpty()) {
                return Optional.of(handlers.get(0));
            }
        }
        // Match: exact FQN, bare method name, "...#method" suffix, or "SimpleClass#method" suffix.
        List<String> methods = jdbc.query("""
                SELECT fqn FROM symbols WHERE repo = ? AND kind = 'METHOD'
                  AND (fqn = ? OR name = ? OR fqn LIKE ? OR fqn LIKE ?)
                ORDER BY (fqn = ?) DESC, length(fqn), fqn LIMIT 1""",
                (rs, i) -> rs.getString(1), repo, in, in, "%#" + in, "%" + in, in);
        return methods.stream().findFirst();
    }
}

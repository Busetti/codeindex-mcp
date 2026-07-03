package com.example.codeindex.web;

import com.example.codeindex.graph.ArchitectureService;
import com.example.codeindex.impact.ImpactService;
import com.example.codeindex.mcp.dto.ChangeImpact;
import com.example.codeindex.mcp.dto.GraphView;
import com.example.codeindex.mcp.dto.HotspotRow;
import com.example.codeindex.mcp.dto.RepoSummary;
import com.example.codeindex.rules.RuleConfig;
import com.example.codeindex.rules.RuleConfigStore;
import com.example.codeindex.store.StructuralRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;

/** REST API backing the web UI: browse folders, start indexing, poll progress, preview hotspots. */
@RestController
@RequestMapping("/api")
public class IndexController {

    private final FileTreeService treeService;
    private final IndexJobService jobService;
    private final StructuralRepository repository;
    private final ImpactService impactService;
    private final ArchitectureService architecture;
    private final RuleConfigStore ruleConfigStore;

    public IndexController(FileTreeService treeService, IndexJobService jobService,
                           StructuralRepository repository, ImpactService impactService,
                           ArchitectureService architecture, RuleConfigStore ruleConfigStore) {
        this.treeService = treeService;
        this.jobService = jobService;
        this.repository = repository;
        this.impactService = impactService;
        this.architecture = architecture;
        this.ruleConfigStore = ruleConfigStore;
    }

    /** Request to start an index run. {@code exclude} holds absolute directory paths to skip. */
    public record IndexRequest(String path, String repo, List<String> exclude) {
    }

    @GetMapping("/tree")
    public TreeNode tree(@RequestParam String path) {
        try {
            return treeService.build(path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/index")
    public IndexJob startIndex(@RequestBody IndexRequest req) {
        if (req.path() == null || req.path().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        String repo = (req.repo() == null || req.repo().isBlank())
                ? Path.of(req.path()).getFileName().toString() : req.repo();
        List<Path> excluded = (req.exclude() == null ? List.<String>of() : req.exclude()).stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> Path.of(s).toAbsolutePath().normalize())
                .toList();
        return jobService.start(req.path(), repo, excluded);
    }

    @GetMapping("/index/{id}")
    public ResponseEntity<IndexJob> jobStatus(@PathVariable String id) {
        IndexJob job = jobService.get(id);
        return job == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(job);
    }

    @GetMapping("/hotspots")
    public List<HotspotRow> hotspots(@RequestParam String repo) {
        return repository.listHotspots(repo, null);
    }

    @GetMapping("/repos")
    public List<RepoSummary> repos() {
        return repository.listRepos();
    }

    @GetMapping("/impact")
    public ChangeImpact impact(@RequestParam String repo, @RequestParam String symbol) {
        return impactService.analyze(repo, symbol, 6);
    }

    /** Per-repo hotspot rule configuration (thresholds + which rules are enabled). */
    @GetMapping("/config")
    public RuleConfig config(@RequestParam String repo) {
        return ruleConfigStore.get(repo);
    }

    public record ConfigRequest(String repo, RuleConfig config) {
    }

    @PutMapping("/config")
    public RuleConfig saveConfig(@RequestBody ConfigRequest req) {
        if (req.repo() == null || req.repo().isBlank() || req.config() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repo and config are required");
        }
        ruleConfigStore.save(req.repo(), req.config());
        return ruleConfigStore.get(req.repo());
    }

    /** Save is separate; this reparses + re-detects with the saved config (no re-embed) as a job. */
    @PostMapping("/config/apply")
    public IndexJob applyConfig(@RequestBody ConfigRequest req) {
        if (req.repo() == null || req.repo().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repo is required");
        }
        if (req.config() != null) {
            ruleConfigStore.save(req.repo(), req.config());
        }
        return jobService.startReapply(req.repo());
    }

    @GetMapping("/graph")
    public GraphView graph(@RequestParam String repo) {
        return architecture.build(repo);
    }

    @GetMapping(value = "/graph.dot", produces = "text/plain")
    public String graphDot(@RequestParam String repo) {
        return architecture.toDot(repo);
    }

    /** One MCP tool exposed by this server. */
    public record ToolInfo(String name, String description) {
    }

    private static final List<ToolInfo> TOOLS = List.of(
            new ToolInfo("list_repos", "List indexed repos with counts; pick the right 'repo' when several exist."),
            new ToolInfo("get_endpoint_map", "HTTP endpoints → handler FQN. Triage entry point."),
            new ToolInfo("trace_call_chain", "Compact endpoint→service→repo tree with [DB]/[HTTP]/[IN-LOOP] tags."),
            new ToolInfo("get_change_impact", "Who calls this method + which endpoints break if you change it."),
            new ToolInfo("get_dependency_cycles", "Circular dependencies between types (architecture health)."),
            new ToolInfo("get_hotspot_report", "Ranked performance findings with fixes."),
            new ToolInfo("search_symbols", "Locate classes/methods/fields — rows only, no bodies."),
            new ToolInfo("get_method_source", "Source of a single method."),
            new ToolInfo("semantic_search", "Natural-language discovery over embedded methods."),
            new ToolInfo("reindex", "Rebuild the index for a source tree."),
            new ToolInfo("ping", "Health probe."));

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return TOOLS;
    }
}

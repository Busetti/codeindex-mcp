package com.example.codeindex.indexer;

import com.example.codeindex.config.CodeindexProperties;
import com.example.codeindex.config.CodeindexProperties.RepoConfig;
import com.example.codeindex.indexer.analysis.HotspotDetector;
import com.example.codeindex.indexer.model.ScanResult;
import com.example.codeindex.mcp.dto.IndexStats;
import com.example.codeindex.rules.RuleConfig;
import com.example.codeindex.rules.RuleConfigStore;
import com.example.codeindex.store.StructuralRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Orchestrates a full parse → persist cycle and resolves default root/repo from configuration. */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    private final JavaCodeIndexer indexer;
    private final HotspotDetector hotspotDetector;
    private final ChunkEmbedder chunkEmbedder;
    private final StructuralRepository repository;
    private final RuleConfigStore ruleConfigStore;
    private final CodeindexProperties props;

    public IndexingService(JavaCodeIndexer indexer, HotspotDetector hotspotDetector,
                           ChunkEmbedder chunkEmbedder, StructuralRepository repository,
                           RuleConfigStore ruleConfigStore, CodeindexProperties props) {
        this.indexer = indexer;
        this.hotspotDetector = hotspotDetector;
        this.chunkEmbedder = chunkEmbedder;
        this.repository = repository;
        this.ruleConfigStore = ruleConfigStore;
        this.props = props;
    }

    /** Repo the read tools default to: the first configured repo. */
    public String defaultRepo() {
        return props.effectiveRepos().get(0).getName();
    }

    /** Index every configured repo (used on startup and by the scheduler); errors are per-repo. */
    public List<IndexStats> reindexConfigured() {
        List<IndexStats> all = new ArrayList<>();
        for (RepoConfig rc : props.effectiveRepos()) {
            try {
                all.add(reindex(rc.getRoot(), rc.getName()));
            } catch (Exception e) {
                log.warn("Reindex of repo '{}' ({}) failed: {}", rc.getName(), rc.getRoot(), e.getMessage());
            }
        }
        return all;
    }

    public IndexStats reindex(String rootPath, String repo) {
        return reindex(rootPath, repo, List.of(), ProgressListener.NOOP);
    }

    /**
     * Full index cycle with optional user-selected excluded directories and a progress callback
     * (used by the async job that backs the web UI).
     */
    public IndexStats reindex(String rootPath, String repo,
                              Collection<Path> excludedPaths, ProgressListener listener) {
        String effectiveRoot = (rootPath == null || rootPath.isBlank()) ? props.getDefaultRoot() : rootPath;
        String effectiveRepo = (repo == null || repo.isBlank()) ? props.getDefaultRepo() : repo;
        Path root = Path.of(effectiveRoot).toAbsolutePath().normalize();

        log.info("Reindexing repo '{}' from {} (excludes: {}, deselected dirs: {})",
                effectiveRepo, root, props.effectiveExcludes(), excludedPaths.size());
        ScanResult r = indexer.scan(root, props.effectiveExcludes(), excludedPaths, listener);
        hotspotDetector.detect(r, ruleConfigStore.get(effectiveRepo));
        repository.replaceRepo(effectiveRepo, r);
        ruleConfigStore.saveRoot(effectiveRepo, root.toString());
        int chunks = chunkEmbedder.reembed(effectiveRepo, r, listener);

        return new IndexStats(effectiveRepo, root.toString(),
                r.symbols().size(), r.endpoints().size(), r.callEdges().size(),
                r.dataAccess().size(), r.externalCalls().size(), r.hotspots().size(), chunks);
    }

    /**
     * Re-run detection with the repo's current {@link RuleConfig} after a config change: reparse (the
     * quality metrics are transient, so a parse is required) and rewrite the structural index, but
     * <b>skip embedding</b> — thresholds don't affect vectors, so this is much cheaper than a reindex.
     */
    public IndexStats reapplyConfig(String repo, ProgressListener listener) {
        String root = ruleConfigStore.getRoot(repo);
        if (root == null || root.isBlank()) {
            throw new IllegalStateException(
                    "No indexed source path is known for repo '" + repo + "'. Index it once before re-applying config.");
        }
        Path rootPath = Path.of(root).toAbsolutePath().normalize();
        RuleConfig cfg = ruleConfigStore.get(repo);
        log.info("Re-applying rule config for repo '{}' from {} (no re-embed)", repo, rootPath);
        ScanResult r = indexer.scan(rootPath, props.effectiveExcludes(), List.of(), listener);
        hotspotDetector.detect(r, cfg);
        repository.replaceRepo(repo, r);

        return new IndexStats(repo, rootPath.toString(),
                r.symbols().size(), r.endpoints().size(), r.callEdges().size(),
                r.dataAccess().size(), r.externalCalls().size(), r.hotspots().size(), 0);
    }
}

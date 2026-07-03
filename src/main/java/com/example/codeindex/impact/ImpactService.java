package com.example.codeindex.impact;

import com.example.codeindex.mcp.dto.ChangeImpact;
import com.example.codeindex.store.StructuralRepository;
import com.example.codeindex.store.StructuralRepository.MethodMeta;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Change-impact analysis: walks the call graph <em>upward</em> from a target method to answer
 * "who calls this, and which HTTP endpoints could break if I change it?". Reuses the same
 * {@code call_edges}/{@code endpoints} data as {@code TraceService}, inverted.
 */
@Service
public class ImpactService {

    private final StructuralRepository repository;

    public ImpactService(StructuralRepository repository) {
        this.repository = repository;
    }

    public ChangeImpact analyze(String repo, String target, int maxDepth) {
        Optional<String> resolved = repository.resolveStart(repo, target);
        if (resolved.isEmpty()) {
            return new ChangeImpact(target, null, List.of(), List.of(), 0,
                    "No indexed method matched: " + target);
        }
        String targetFqn = resolved.get();
        Map<String, List<String>> reverse = repository.loadReverseCallGraph(repo);
        Map<String, MethodMeta> meta = repository.loadMethodMeta(repo);
        Map<String, List<String>> endpointsByHandler = repository.endpointsByHandler(repo);
        int depth = maxDepth <= 0 ? 6 : maxDepth;

        // Upward closure (all transitive callers) for impacted-endpoint detection.
        Set<String> closure = new LinkedHashSet<>();
        collectClosure(targetFqn, depth, reverse, new LinkedHashSet<>(), closure);

        Set<String> impacted = new TreeSet<>();
        for (String fqn : closure) {
            endpointsByHandler.getOrDefault(fqn, List.of()).forEach(impacted::add);
        }
        endpointsByHandler.getOrDefault(targetFqn, List.of()).forEach(impacted::add);

        List<String> directCallers = new ArrayList<>();
        reverse.getOrDefault(targetFqn, List.of()).forEach(c -> directCallers.add(shortName(c)));

        StringBuilder rendered = new StringBuilder(shortName(targetFqn)).append("  (target)\n");
        render(targetFqn, 1, depth, reverse, meta, endpointsByHandler, new LinkedHashSet<>(), rendered);

        return new ChangeImpact(target, targetFqn, directCallers,
                new ArrayList<>(impacted), closure.size(), rendered.toString());
    }

    private void collectClosure(String fqn, int maxDepth, Map<String, List<String>> reverse,
                                Set<String> path, Set<String> closure) {
        if (maxDepth <= 0 || !path.add(fqn)) {
            return;
        }
        for (String caller : reverse.getOrDefault(fqn, List.of())) {
            closure.add(caller);
            collectClosure(caller, maxDepth - 1, reverse, path, closure);
        }
        path.remove(fqn);
    }

    private void render(String fqn, int depth, int maxDepth, Map<String, List<String>> reverse,
                        Map<String, MethodMeta> meta, Map<String, List<String>> endpointsByHandler,
                        Set<String> visited, StringBuilder out) {
        if (depth > maxDepth || !visited.add(fqn)) {
            return;
        }
        for (String caller : reverse.getOrDefault(fqn, List.of())) {
            out.append("  ".repeat(depth)).append("<- ").append(shortName(caller));
            MethodMeta m = meta.get(caller);
            if (m != null && m.stereotype() != null) {
                out.append("  [").append(m.stereotype()).append(']');
            }
            List<String> eps = endpointsByHandler.get(caller);
            if (eps != null && !eps.isEmpty()) {
                out.append("  {").append(String.join(", ", eps)).append('}');
            }
            out.append('\n');
            render(caller, depth + 1, maxDepth, reverse, meta, endpointsByHandler, visited, out);
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

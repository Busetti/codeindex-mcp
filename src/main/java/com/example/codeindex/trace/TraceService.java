package com.example.codeindex.trace;

import com.example.codeindex.mcp.dto.CallChain;
import com.example.codeindex.mcp.dto.CallChain.TraceLine;
import com.example.codeindex.store.StructuralRepository;
import com.example.codeindex.store.StructuralRepository.Edge;
import com.example.codeindex.store.StructuralRepository.MethodMeta;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks the persisted call graph from an endpoint/method downwards, keeping only the
 * Spring-bean path (controller→service→repository/component) and annotating each node with the
 * signals a performance triage cares about: transaction boundaries, DB access, outbound HTTP, and
 * whether the call happens inside a loop.
 */
@Service
public class TraceService {

    private static final Set<String> BEAN_STEREOTYPES = Set.of("CONTROLLER", "SERVICE", "REPOSITORY", "COMPONENT");

    private final StructuralRepository repository;

    public TraceService(StructuralRepository repository) {
        this.repository = repository;
    }

    public CallChain trace(String repo, String start, int maxDepth) {
        Optional<String> startFqn = repository.resolveStart(repo, start);
        if (startFqn.isEmpty()) {
            return new CallChain(start, null, List.of(), "No indexed method/endpoint matched: " + start);
        }
        Map<String, MethodMeta> meta = repository.loadMethodMeta(repo);
        Map<String, List<Edge>> graph = repository.loadCallGraph(repo);
        Set<String> dbOwners = repository.ownersWith(repo, "data_access");
        Set<String> httpOwners = repository.ownersWith(repo, "external_calls");

        List<TraceLine> lines = new ArrayList<>();
        StringBuilder rendered = new StringBuilder(start).append("  ->  ").append(startFqn.get()).append('\n');
        visit(startFqn.get(), 1, false, maxDepth <= 0 ? 6 : maxDepth,
                meta, graph, dbOwners, httpOwners, new HashSet<>(), lines, rendered);
        return new CallChain(start, startFqn.get(), lines, rendered.toString());
    }

    private void visit(String fqn, int depth, boolean viaLoop, int maxDepth,
                       Map<String, MethodMeta> meta, Map<String, List<Edge>> graph,
                       Set<String> dbOwners, Set<String> httpOwners, Set<String> visited,
                       List<TraceLine> lines, StringBuilder rendered) {
        List<String> tags = tagsFor(fqn, viaLoop, meta, dbOwners, httpOwners);
        lines.add(new TraceLine(depth, fqn, viaLoop, tags));
        rendered.append("  ".repeat(depth)).append("-> ").append(shortName(fqn));
        if (!tags.isEmpty()) {
            rendered.append("  ").append(String.join(" ", tags));
        }
        rendered.append('\n');

        if (depth >= maxDepth || !visited.add(fqn)) {
            return;
        }
        for (Edge e : graph.getOrDefault(fqn, List.of())) {
            MethodMeta calleeMeta = meta.get(e.calleeFqn());
            boolean isBean = calleeMeta != null && calleeMeta.stereotype() != null
                    && BEAN_STEREOTYPES.contains(calleeMeta.stereotype());
            boolean touchesIo = dbOwners.contains(e.calleeFqn()) || httpOwners.contains(e.calleeFqn());
            if (isBean || touchesIo) {
                visit(e.calleeFqn(), depth + 1, e.inLoop(), maxDepth,
                        meta, graph, dbOwners, httpOwners, visited, lines, rendered);
            }
        }
    }

    private List<String> tagsFor(String fqn, boolean viaLoop, Map<String, MethodMeta> meta,
                                 Set<String> dbOwners, Set<String> httpOwners) {
        List<String> tags = new ArrayList<>();
        MethodMeta m = meta.get(fqn);
        if (m != null && m.stereotype() != null) {
            tags.add("[" + m.stereotype() + "]");
        }
        if (m != null && m.annotations() != null && m.annotations().contains("Transactional")) {
            tags.add("[@Transactional]");
        }
        if (dbOwners.contains(fqn)) {
            tags.add("[DB]");
        }
        if (httpOwners.contains(fqn)) {
            tags.add("[HTTP]");
        }
        if (viaLoop) {
            tags.add("[IN-LOOP]");
        }
        return tags;
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

package com.example.codeindex.graph;

import com.example.codeindex.mcp.dto.GraphView;
import com.example.codeindex.store.StructuralRepository;
import com.example.codeindex.store.StructuralRepository.Edge;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds a type-level dependency graph from the method call graph (collapsing {@code Owner#method}
 * edges to {@code Owner→Owner}), restricted to indexed types. Detects circular dependencies via
 * Tarjan's strongly-connected-components algorithm.
 */
@Service
public class ArchitectureService {

    private final StructuralRepository repository;

    public ArchitectureService(StructuralRepository repository) {
        this.repository = repository;
    }

    public GraphView build(String repo) {
        Map<String, String> types = repository.loadTypeStereotypes(repo);
        Map<String, List<Edge>> callGraph = repository.loadCallGraph(repo);

        // Collapse method-call edges to type→type, counting occurrences (skip self and external types).
        Map<String, Map<String, Integer>> deps = new HashMap<>();
        callGraph.forEach((caller, edges) -> {
            String from = owner(caller);
            if (!types.containsKey(from)) {
                return;
            }
            for (Edge e : edges) {
                String to = owner(e.calleeFqn());
                if (!types.containsKey(to) || to.equals(from)) {
                    continue;
                }
                deps.computeIfAbsent(from, k -> new HashMap<>()).merge(to, 1, Integer::sum);
            }
        });

        // Nodes = types that participate in any edge (fall back to all types if there are none).
        Set<String> nodeIds = new TreeSet<>();
        List<GraphView.Edge> edges = new ArrayList<>();
        deps.forEach((from, tos) -> tos.forEach((to, count) -> {
            nodeIds.add(from);
            nodeIds.add(to);
            edges.add(new GraphView.Edge(from, to, count));
        }));
        if (nodeIds.isEmpty()) {
            nodeIds.addAll(types.keySet());
        }

        List<GraphView.Node> nodes = new ArrayList<>();
        for (String id : nodeIds) {
            nodes.add(new GraphView.Node(id, simple(id), types.get(id)));
        }

        List<List<String>> cycles = detectCycles(nodeIds, deps);
        return new GraphView(nodes, edges, cycles);
    }

    /** Graphviz DOT rendering (offline-friendly export). */
    public String toDot(String repo) {
        GraphView g = build(repo);
        StringBuilder sb = new StringBuilder("digraph codeindex {\n  rankdir=LR;\n  node [shape=box, style=rounded];\n");
        for (GraphView.Node n : g.nodes()) {
            String color = switch (n.stereotype() == null ? "" : n.stereotype()) {
                case "CONTROLLER" -> "#4f9cf9";
                case "SERVICE" -> "#37c871";
                case "REPOSITORY" -> "#ffb020";
                case "COMPONENT" -> "#b98bff";
                default -> "#cccccc";
            };
            sb.append("  \"").append(n.label()).append("\" [color=\"").append(color).append("\"];\n");
        }
        for (GraphView.Edge e : g.edges()) {
            sb.append("  \"").append(simple(e.source())).append("\" -> \"").append(simple(e.target()))
              .append("\" [label=\"").append(e.count()).append("\"];\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ---- Tarjan SCC (cycles are SCCs of size > 1) --------------------------

    private List<List<String>> detectCycles(Set<String> nodes, Map<String, Map<String, Integer>> deps) {
        Tarjan t = new Tarjan(deps);
        List<List<String>> cycles = new ArrayList<>();
        for (String n : nodes) {
            if (!t.index.containsKey(n)) {
                t.strongConnect(n, cycles);
            }
        }
        return cycles;
    }

    private final class Tarjan {
        final Map<String, Map<String, Integer>> adj;
        final Map<String, Integer> index = new HashMap<>();
        final Map<String, Integer> low = new HashMap<>();
        final Deque<String> stack = new ArrayDeque<>();
        final Set<String> onStack = new HashSet<>();
        int counter = 0;

        Tarjan(Map<String, Map<String, Integer>> adj) {
            this.adj = adj;
        }

        void strongConnect(String v, List<List<String>> cycles) {
            index.put(v, counter);
            low.put(v, counter);
            counter++;
            stack.push(v);
            onStack.add(v);
            for (String w : adj.getOrDefault(v, Map.of()).keySet()) {
                if (!index.containsKey(w)) {
                    strongConnect(w, cycles);
                    low.put(v, Math.min(low.get(v), low.get(w)));
                } else if (onStack.contains(w)) {
                    low.put(v, Math.min(low.get(v), index.get(w)));
                }
            }
            if (low.get(v).equals(index.get(v))) {
                LinkedHashSet<String> scc = new LinkedHashSet<>();
                String w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    scc.add(simple(w));
                } while (!w.equals(v));
                if (scc.size() > 1) {
                    cycles.add(new ArrayList<>(scc));
                }
            }
        }
    }

    private String owner(String memberFqn) {
        if (memberFqn == null) {
            return "";
        }
        int i = memberFqn.indexOf('#');
        return i < 0 ? memberFqn : memberFqn.substring(0, i);
    }

    private String simple(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }
}

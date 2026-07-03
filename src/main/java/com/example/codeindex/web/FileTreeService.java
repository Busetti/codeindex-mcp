package com.example.codeindex.web;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Builds a bounded directory tree (dirs only, with per-dir .java counts) for the UI to render. */
@Service
public class FileTreeService {

    private static final Set<String> DEFAULT_EXCLUDED_NAMES = Set.of(
            "target", "build", "out", "bin", ".git", "node_modules",
            "generated", "generated-sources", "test");

    private static final int MAX_DEPTH = 8;
    private static final int MAX_NODES = 4000;

    public TreeNode build(String path) {
        Path root = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Not a directory: " + root);
        }
        return node(root, 0, new int[]{MAX_NODES});
    }

    private TreeNode node(Path dir, int depth, int[] budget) {
        int javaCount = 0;
        List<Path> subdirs = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : (Iterable<Path>) entries::iterator) {
                if (Files.isDirectory(p)) {
                    subdirs.add(p);
                } else if (p.toString().endsWith(".java")) {
                    javaCount++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        subdirs.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<TreeNode> children = new ArrayList<>();
        if (depth < MAX_DEPTH) {
            for (Path sd : subdirs) {
                if (budget[0]-- <= 0) {
                    break;
                }
                children.add(node(sd, depth + 1, budget));
            }
        }
        String name = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
        return new TreeNode(name, dir.toString(), javaCount, isDefaultExcluded(name), children);
    }

    private boolean isDefaultExcluded(String name) {
        return name.startsWith(".") || DEFAULT_EXCLUDED_NAMES.contains(name);
    }
}

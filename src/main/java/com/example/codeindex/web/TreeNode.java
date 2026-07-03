package com.example.codeindex.web;

import java.util.List;

/** A directory in the folder-structure tree shown by the UI. */
public record TreeNode(
        String name,
        String path,            // absolute path (sent back as an exclude selection)
        int javaFiles,          // .java files directly in this directory
        boolean defaultExcluded,// build/VCS/test dirs — the UI pre-checks these as "skip"
        List<TreeNode> children) {
}

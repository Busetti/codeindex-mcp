package com.example.codeindex.mcp;

import com.example.codeindex.mcp.dto.RepoSummary;
import com.example.codeindex.store.StructuralRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import java.util.List;

/** Repo discovery — lets a client pick the right {@code repo} argument when several are indexed. */
@Component
public class RepoTools {

    private final StructuralRepository repository;

    public RepoTools(StructuralRepository repository) {
        this.repository = repository;
    }

    @McpTool(name = "list_repos",
            description = "List the codebases (repos) currently indexed by this server, each with its "
                    + "symbol/endpoint/hotspot/chunk counts. When more than one repo is indexed, call this "
                    + "FIRST to choose the correct 'repo' argument to pass to the other tools "
                    + "(trace_call_chain, get_hotspot_report, search_symbols, semantic_search, etc.).")
    public List<RepoSummary> listRepos() {
        return repository.listRepos();
    }
}

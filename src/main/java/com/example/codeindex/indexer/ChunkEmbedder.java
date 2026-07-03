package com.example.codeindex.indexer;

import com.example.codeindex.indexer.model.ScanResult;
import com.example.codeindex.indexer.model.Symbol;
import com.example.codeindex.mcp.dto.SemanticHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Embeds method-level code chunks into pgvector (via Spring AI's {@link VectorStore}) using the
 * local ONNX model, and serves semantic ("find code about X") lookups. Complements the structural
 * tools: use this when you don't know the class/method names.
 */
@Component
public class ChunkEmbedder {

    private static final Logger log = LoggerFactory.getLogger(ChunkEmbedder.class);
    private static final int SNIPPET_LEN = 240;
    private static final int EMBED_BATCH = 16;

    private final VectorStore vectorStore;

    public ChunkEmbedder(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int reembed(String repo, ScanResult r) {
        return reembed(repo, r, ProgressListener.NOOP);
    }

    /** Replace all chunks for {@code repo} with freshly embedded method chunks. Returns chunk count. */
    public int reembed(String repo, ScanResult r, ProgressListener listener) {
        ProgressListener pl = listener == null ? ProgressListener.NOOP : listener;
        try {
            vectorStore.delete(repoFilter(repo));
        } catch (Exception e) {
            log.debug("No existing chunks to delete for repo {} ({})", repo, e.getMessage());
        }

        List<Document> docs = new ArrayList<>();
        for (Symbol s : r.symbols()) {
            if (!"METHOD".equals(s.kind()) || s.source() == null) {
                continue;
            }
            Map<String, Object> md = new HashMap<>();
            md.put("repo", repo);
            md.put("fqn", s.fqn());
            md.put("kind", s.kind());
            md.put("file", s.filePath());
            md.put("line", s.startLine());
            String content = s.fqn() + "\n"
                    + (s.signature() == null ? "" : s.signature() + "\n")
                    + s.source();
            docs.add(Document.builder().text(content).metadata(md).build());
        }

        int total = docs.size();
        pl.onProgress("embedding", 0, total);
        for (int i = 0; i < total; i += EMBED_BATCH) {
            List<Document> batch = docs.subList(i, Math.min(i + EMBED_BATCH, total));
            vectorStore.add(batch);
            pl.onProgress("embedding", Math.min(i + EMBED_BATCH, total), total);
        }
        log.info("Embedded {} method chunks for repo {}", total, repo);
        return total;
    }

    public List<SemanticHit> search(String repo, String query, int topK) {
        SearchRequest req = SearchRequest.builder()
                .query(query)
                .topK(topK <= 0 ? 5 : topK)
                .filterExpression(repoFilter(repo))
                .build();
        List<Document> hits = vectorStore.similaritySearch(req);
        List<SemanticHit> out = new ArrayList<>();
        if (hits == null) {
            return out;
        }
        for (Document d : hits) {
            Map<String, Object> md = d.getMetadata();
            String fqn = str(md.get("fqn"));
            String location = str(md.get("file")) + ":" + str(md.get("line"));
            out.add(new SemanticHit(fqn, str(md.get("kind")), location, d.getScore(), snippet(d.getText())));
        }
        return out;
    }

    private String repoFilter(String repo) {
        return "repo == '" + repo.replace("'", "") + "'";
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= SNIPPET_LEN ? t : t.substring(0, SNIPPET_LEN) + " …";
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }
}

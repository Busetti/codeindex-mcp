package com.example.codeindex.indexer.model;

/** An outbound network call attributed to the enclosing method (populated in P2). */
public record ExternalCallFact(
        String ownerFqn,
        String kind,      // REST_TEMPLATE | WEB_CLIENT | FEIGN | KAFKA
        String detail,
        int line,
        boolean inLoop
) {
}

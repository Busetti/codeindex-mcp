package com.example.codeindex.indexer.model;

/** A database-access fact attributed to the enclosing method (populated in P2). */
public record DataAccessFact(
        String ownerFqn,
        String kind,      // REPOSITORY_METHOD | JPQL | NATIVE_QUERY | JDBC | ENTITY_MANAGER
        String detail,    // query text or repository method name
        int line,
        boolean inLoop
) {
}

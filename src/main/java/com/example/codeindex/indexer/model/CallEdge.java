package com.example.codeindex.indexer.model;

/**
 * A method-to-method call. {@code calleeFqn} is filled by best-effort resolution (receiver's
 * declared type mapped to a known symbol); {@code calleeSimple} always holds the textual callee.
 * {@code inLoop} marks calls inside a for/while/do/forEach body — the primary N+1 signal.
 */
public record CallEdge(
        String callerFqn,
        String calleeFqn,     // nullable when unresolved
        String calleeSimple,
        int line,
        boolean inLoop
) {
}

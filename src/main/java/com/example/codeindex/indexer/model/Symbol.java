package com.example.codeindex.indexer.model;

/**
 * A class / interface / enum / method / field discovered by the parser.
 *
 * <p>FQN conventions:
 * <ul>
 *   <li>types  → {@code com.example.orders.OrderService}</li>
 *   <li>methods→ {@code com.example.orders.OrderService#listAllOrders} (overloads collapse; the
 *       full signature is kept separately in {@link #signature()})</li>
 *   <li>fields → {@code com.example.orders.OrderService#orderRepository}</li>
 * </ul>
 * {@code source} is populated for methods only, so get_method_source never re-reads files.
 */
public record Symbol(
        String kind,          // CLASS | INTERFACE | ENUM | METHOD | FIELD
        String fqn,
        String name,
        String signature,     // nullable for types
        String ownerFqn,      // nullable for top-level types
        String stereotype,    // CONTROLLER | SERVICE | REPOSITORY | COMPONENT | null
        String annotations,   // comma-separated simple names
        String filePath,
        int startLine,
        int endLine,
        String source         // method body source, else null
) {
}

package com.example.codeindex.indexer.model;

import java.util.ArrayList;
import java.util.List;

/** Mutable accumulator produced by a single indexing run, then persisted atomically per repo. */
public class ScanResult {

    private final List<Symbol> symbols = new ArrayList<>();
    private final List<EndpointInfo> endpoints = new ArrayList<>();
    private final List<CallEdge> callEdges = new ArrayList<>();
    private final List<DataAccessFact> dataAccess = new ArrayList<>();
    private final List<ExternalCallFact> externalCalls = new ArrayList<>();
    private final List<HotspotFinding> hotspots = new ArrayList<>();
    private final List<MethodMetric> metrics = new ArrayList<>();

    public List<Symbol> symbols() { return symbols; }
    public List<EndpointInfo> endpoints() { return endpoints; }
    public List<CallEdge> callEdges() { return callEdges; }
    public List<DataAccessFact> dataAccess() { return dataAccess; }
    public List<ExternalCallFact> externalCalls() { return externalCalls; }
    public List<HotspotFinding> hotspots() { return hotspots; }

    /** Transient per-method quality metrics; consumed by HotspotDetector, not persisted. */
    public List<MethodMetric> metrics() { return metrics; }
}

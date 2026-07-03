# Implementation Guide — codeindex-mcp

How the server is built and why. For running it see [USAGE.md](USAGE.md).

---

## 1. Design goal

Turn a Java/Spring codebase into a **pre-computed, queryable knowledge layer** so an LLM triages
issues cheaply. Token savings come from two levers:

1. **Retrieval precision** — return the exact slice (one method, one query), never a file.
2. **Structural compression** — an endpoint-map row ≈ 15 tokens vs. ~800 to scan a controller; a
   call chain is N short lines; a hotspot finding lets the model *validate* rather than *derive*.

Two complementary indexes back the tools:

- **Structural** (deterministic): symbols, endpoints, call graph, data-access/external-call facts,
  pre-computed hotspots. Answers exact "what calls what / what's slow" questions.
- **Semantic** (pgvector + local embeddings): method chunks for fuzzy "find code about X" discovery.

---

## 2. Stack

| Concern | Choice |
|--------|--------|
| Runtime | Spring Boot 4.1, Java 21+ |
| MCP | Spring AI 2.0 `spring-ai-starter-mcp-server-webmvc`, Streamable HTTP, `@McpTool` annotations |
| Parsing | JavaParser 3.28 (no SymbolSolver) |
| Storage | PostgreSQL + pgvector (`pgvector/pgvector` image) |
| Embeddings | Spring AI `TransformersEmbeddingModel` (ONNX all-MiniLM-L6-v2, 384-dim, in-process) |
| Migrations | Flyway (`spring-boot-flyway` module) |

Everything runs locally: source is parsed on-host and embeddings are computed in-process, so **code
never leaves the network** and there is **no per-token embedding cost**.

---

## 3. Component map

```
CodeIndexMcpApplication                 @SpringBootApplication + @EnableScheduling + @ConfigurationPropertiesScan
config/CodeindexProperties              codeindex.* binding (repos, schedule, excludes)
indexer/
  JavaCodeIndexer                       parse source tree -> ScanResult (symbols, endpoints, edges, facts, metrics)
  analysis/HotspotDetector              ScanResult -> HotspotFinding[] (perf + quality + security rules)
  analysis/MethodMetrics                MethodDeclaration -> MethodMetric (complexity, nesting, heuristics)
  ChunkEmbedder                         ScanResult -> pgvector chunks; semantic search
  IndexingService                       orchestrates parse -> detect -> persist -> embed; multi-repo
  StartupIndexer                        indexes configured repos on boot
  ScheduledReindexer                    @Scheduled reindex of all configured repos
  model/                                Symbol, EndpointInfo, CallEdge, DataAccessFact,
                                        ExternalCallFact, HotspotFinding, MethodMetric, ScanResult
store/StructuralRepository              JDBC persistence + query helpers (search, endpoints, trace,
                                        hotspots, reverse graph, repo summaries, type stereotypes)
trace/TraceService                      downstream call-graph walk with tagging
impact/ImpactService                    upstream (reverse) call-graph walk → change impact
graph/ArchitectureService               type dependency graph + Tarjan cycle detection + DOT export
web/                                    IndexController (REST), FileTreeService, IndexJob(+Service)
mcp/                                    @McpTool beans: CodeSearchTools, TraceTools, HotspotTools,
                                        SemanticTools, ImpactTools, GraphTools, RepoTools,
                                        IndexAdminTools, PingTools
mcp/dto/                                compact tool response records
```

Data flow of one `reindex`:

```
JavaCodeIndexer.scan(root, excludes)
      → ScanResult                         (in memory)
HotspotDetector.detect(ScanResult)         (appends findings)
StructuralRepository.replaceRepo(repo, …)  (one transaction, replace-all)
ChunkEmbedder.reembed(repo, ScanResult)    (delete+add pgvector chunks)
```

---

## 4. Storage schema

Flyway `V1__schema.sql` creates the structural tables; the pgvector `code_chunks` table is owned by
Spring AI's `PgVectorStore` (`initialize-schema: true`). A `repo` column namespaces every row so one
database holds many codebases.

| Table | Key columns |
|-------|-------------|
| `symbols` | `kind` (CLASS/INTERFACE/ENUM/METHOD/FIELD), `fqn`, `signature`, `owner_fqn`, `stereotype`, `annotations`, `file_path`, `start_line`, `source` (methods only) |
| `endpoints` | `http_method`, `path`, `handler_fqn`, `controller_fqn` |
| `call_edges` | `caller_fqn`, `callee_fqn` (resolved, nullable), `callee_simple`, `line`, `in_loop` |
| `data_access` | `owner_fqn`, `kind` (REPOSITORY_METHOD/JPQL/NATIVE_QUERY/…), `detail`, `line`, `in_loop` |
| `external_calls` | `owner_fqn`, `kind` (REST_TEMPLATE/WEB_CLIENT), `detail`, `line`, `in_loop` |
| `hotspots` | `category`, `severity`, `symbol_fqn`, `file_path`, `line`, `rationale` |
| `code_chunks` | pgvector: `content`, `metadata` (repo/fqn/file/line), `embedding vector(384)` |

**FQN convention:** types `a.b.C`; methods/fields `a.b.C#member` (overloads collapse; full signature
kept in `symbols.signature`). `source` for methods lets `get_method_source` avoid re-reading files.

---

## 5. The indexing pipeline (`JavaCodeIndexer`)

No SymbolSolver is used, so the indexer can parse code it **cannot compile** — the target's
dependencies need not be on our classpath. Two passes:

**Pass 1 — inventory.** Walk `.java` files (honoring exclude globs), parse each, and record for every
declared type: its FQN by simple name, its Spring stereotype (`@RestController`/`@Service`/…), and
whether it is a repository (stereotype `@Repository` or `extends *Repository`).

**Pass 2 — facts.** For each type emit a `Symbol`; for each field record `name → declared type`; for
each method emit a `Symbol` (with source), then:

- **Endpoints** from `@GetMapping`/`@RequestMapping`/… — HTTP verb + joined class/method path.
- **Call edges** from every `MethodCallExpr`, with best-effort callee resolution (below) and an
  `in_loop` flag.
- **Data-access facts**: `@Query` on the method (JPQL vs `nativeQuery`), and calls whose receiver
  resolves to a repository type (`REPOSITORY_METHOD`).
- **External-call facts**: calls whose receiver type is `RestTemplate`/`WebClient`/`RestClient`.

### Callee resolution without a classpath

For a call `receiver.method(...)`:
1. Build a per-method map `name → simple type` from class fields, parameters, and local variables.
2. Map the receiver's simple type to an FQN via **imports → same package → any declared type**.
3. If it resolves to a known type, `callee_fqn = typeFqn + "#" + method`; otherwise keep only
   `callee_simple`. Bare/`this` calls resolve to the current type.

This reconstructs endpoint→service→repository chains reliably for typical Spring code without full
type inference. Trade-off: overloads collapse and calls through interfaces/inheritance may stay
unresolved (see §10).

### `in_loop` detection

Walk ancestors of the call up to the method boundary; `true` if any is a `for`/`for-each`/`while`/
`do` statement. This is the primary N+1 signal.

---

## 6. Hotspot heuristics (`HotspotDetector`)

Pure functions over the collected facts — deterministic, no code execution, no LLM.

**Performance & data-access** (over `dataAccess`/`externalCalls`/`callEdges` facts):

| Category | Severity | Rule |
|----------|----------|------|
| `N_PLUS_ONE` | HIGH | a `REPOSITORY_METHOD` data-access fact with `in_loop = true` |
| `EXTERNAL_CALL_IN_LOOP` | HIGH | an external call `in_loop`, **or** a loop edge into a method that itself does HTTP (transitive per-row remote cost) |
| `UNPAGINATED_QUERY` | MEDIUM | a repository call to `findAll` (no `Pageable`) |
| `NATIVE_SELECT_STAR` | LOW | a native `@Query` containing `SELECT *` |

**Maintainability / error-handling / security** (over `MethodMetric`s — AST metrics collected during
the parse pass, see §6b):

| Category | Severity | Rule |
|----------|----------|------|
| `GOD_CLASS` | MEDIUM | class span > 500 LOC **or** > 20 methods |
| `LONG_METHOD` | MEDIUM | method > 80 LOC |
| `HIGH_COMPLEXITY` | MEDIUM | cyclomatic complexity > 10 |
| `DEEP_NESTING` | MEDIUM | control-flow nesting depth > 4 |
| `TOO_MANY_PARAMS` | LOW | > 5 parameters |
| `SILENT_FAILURE` | MEDIUM | an empty `catch` block |
| `STRING_CONCAT_IN_LOOP` | MEDIUM | String `+`/`+=` inside a loop |
| `MISSING_ERROR_HANDLING` | MEDIUM | a method making an outbound call with no `try` in its body |
| `HARDCODED_SECRET` | MEDIUM | *(heuristic)* a secret-named var/field assigned a string literal |
| `SQL_INJECTION_RISK` | MEDIUM | *(heuristic)* string concatenation passed to a query sink (`createQuery`, `executeQuery`, …) |

Each finding carries `file:line` and a one-line fix. Performance findings are attributed to the
**caller** method (where the fix belongs), e.g. the N+1 is reported on `OrderService#listAllOrders`.
**Security rules are heuristic pattern matches** (no data-flow/taint analysis) — their rationale is
prefixed `(heuristic — verify)` and severity is capped so they read as leads, not proven vulnerabilities.

## 6b. Method metrics (`MethodMetrics`)

The AST-based signals above (complexity, nesting, empty-catch, string-concat-in-loop, secret/SQL
heuristics) can't be recovered from the persisted model, so they're computed **during parsing** where
the `MethodDeclaration` AST is in hand: `JavaCodeIndexer.processMethod` calls
`MethodMetrics.compute(md)` and appends a `MethodMetric` to a **transient** `ScanResult.metrics()`
list. `HotspotDetector` consumes it in-memory (before persistence), so metrics are never stored.
Cyclomatic complexity = `1 + #(if/for/while/do/catch/case/ternary/&&/||)`; nesting is a recursive
walk that treats `else if` as one level.

## 6c. Per-repo rule config (`RuleConfig` / `RuleConfigStore`)

Thresholds are **not hardcoded** — `HotspotDetector.detect(ScanResult, RuleConfig)` reads them from a
per-repo `RuleConfig` (the `detect(ScanResult)` overload uses `RuleConfig.defaults()`, preserving the
original numbers). `RuleConfigStore` persists config + the last-indexed source `root` in the
`repo_config` table (Flyway `V2`). Every rule is also gated by `cfg.enabled(category)`, so a repo can
silence noisy categories via `disabledRules`. **All gating lives in the detector** — the field-level
secret heuristic is recorded as a `MethodMetric(secretHit=true)` rather than emitted from the parser,
so `disabledRules` applies uniformly. `IndexController` exposes `GET/PUT /api/config` and
`POST /api/config/apply`; apply runs `IndexingService.reapplyConfig` — a reparse + re-detect that
**skips embedding** (thresholds don't change vectors), so it's much cheaper than a full reindex.

---

## 7. Call-chain tracing (`TraceService`)

`trace_call_chain` loads three things once (method metadata, the resolved call graph, and the sets of
methods that do DB/HTTP) then does a depth-first walk from the resolved start:

- **Start resolution**: a `/path` → endpoint handler; else an exact/bare method name.
- **Pruning**: descend only into Spring beans (stereotyped) or methods that touch I/O — this drops
  noise like `List#add` and domain getters, keeping the tree focused on the request path.
- **Tagging** per node: `[STEREOTYPE]`, `[@Transactional]`, `[DB]`, `[HTTP]`, `[IN-LOOP]`.
- Output is both a rendered indented tree (cheapest for an LLM) and a structured `lines` list.

A `visited` set bounds cycles; `maxDepth` (default 6) bounds depth.

---

## 7b. Change-impact (`ImpactService`)

`get_change_impact` is `TraceService` inverted — it walks the **reverse** call graph
(`loadReverseCallGraph`: callee → callers) upward from a target method. It returns the direct
callers, a rendered upward tree, and — by intersecting the target's **upward closure** with
`endpointsByHandler` — the **HTTP endpoints whose behaviour could change**. This is the "blast radius"
of a change: who depends on this code and which API entry points are affected. Runs entirely off the
existing `call_edges`/`endpoints` tables; no new indexing.

## 7c. Architecture graph (`ArchitectureService`)

`GET /api/graph` / `get_dependency_cycles` build a **type-level** dependency graph: the method call
graph collapsed to `Owner→Owner` edges, restricted to indexed types (external/JDK types dropped),
with per-edge call counts. **Circular dependencies** are the strongly-connected components of size > 1,
found via **Tarjan's algorithm**. `GET /api/graph.dot` emits Graphviz DOT (offline export); the web UI
renders the JSON with Cytoscape.js (nodes coloured by stereotype).

---

## 8. Semantic layer (`ChunkEmbedder`)

On each reindex: delete the repo's existing chunks (`vectorStore.delete("repo == '…'")`), then embed
one chunk per method (`fqn + signature + source`) with metadata `{repo, fqn, kind, file, line}` and
`add` them. Embeddings are produced in-process by the ONNX MiniLM model (384-dim).

`semantic_search` issues a `SearchRequest` with `filterExpression("repo == '…'")`, returns FQN,
`file:line`, similarity score, and a truncated snippet — never a whole file. It complements the
structural tools: use it when the class/method names are unknown.

---

## 9. Freshness: startup + schedule

- `StartupIndexer` (`ApplicationRunner`) indexes every configured repo on boot when
  `reindex-on-startup` is true.
- `ScheduledReindexer` (`@Scheduled(cron = "${codeindex.schedule.cron:-}")`, gated by
  `schedule.enabled`) reindexes all configured repos on the cron. `@EnableScheduling` is on the main
  app. Both reuse `IndexingService.reindexConfigured()`, which iterates `effectiveRepos()` and
  isolates per-repo failures.

Persistence is **replace-all per repo in one transaction**, so a reindex never exposes a partial
graph and re-runs are idempotent.

---

## 10. Notable engineering details

- **JavaParser × modern javac.** `javac` 23+ rejects a JavaParser library generic (`TypeDeclaration`
  implementing `NodeWithMembers`) whenever `TypeDeclaration<?>` is *captured* — e.g. via
  `Stream.concat(...)` of two declaration streams, or calling `getFields()/getMethods()`. The indexer
  sidesteps this by iterating the concrete declaration types separately and reading members from
  `Node#getChildNodes()`. Result: it compiles on JDK 21/23/25 with no toolchain pinning.
- **Spring Boot 4 modular autoconfig.** Flyway support moved out of `spring-boot-autoconfigure` into
  its own `spring-boot-flyway` module — it must be a dependency or migrations silently don't run.
- **Testcontainers 2.0** renamed modules (`testcontainers-postgresql`, `testcontainers-junit-jupiter`).
- **Spring AI 2.0** uses `@McpTool`/`@McpToolParam` from `org.springframework.ai.mcp.annotation`,
  auto-registered via `spring.ai.mcp.server.annotation-scanner.enabled: true`; SSE transport is
  deprecated in favour of `STREAMABLE`.

---

## 11. Known limitations & extension points

| Limitation | Where to extend |
|------------|-----------------|
| Java only | `JavaCodeIndexer` is language-specific; a Kotlin parser could feed the same `ScanResult`. |
| Overloads collapse to `Owner#name` | Include parameter arity in the method FQN if disambiguation is needed. |
| Interface/inherited calls may not resolve | Add an inheritance map in pass 1, or enable JavaParser SymbolSolver when the classpath is available. |
| Full re-embed each reindex | Schema has room for content-hash upserts to make embedding incremental. |
| Heuristic hotspots | Add rules (missing `@Transactional(readOnly)`, unbounded fetch, blocking-in-reactive) in `HotspotDetector`. |
| No runtime correlation | Findings are static; joining them to APM latency would rank by real impact. |

---

## 12. Tests

`SampleTargetIndexTest` runs the parser + `HotspotDetector` against `examples/sample-target` with **no
database** — fast and deterministic. It asserts the endpoint discovery, the in-loop `findById` edge,
and the four expected hotspot categories. Repository/semantic paths are exercised end-to-end against a
live pgvector during development (see the walkthrough in [USAGE.md](USAGE.md)).

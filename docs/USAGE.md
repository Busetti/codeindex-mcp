# Usage Guide — codeindex-mcp

How to run the server, point it at your code, register it with an MCP client, and use each tool.
For internals see [IMPLEMENTATION.md](IMPLEMENTATION.md).

---

## 1. Prerequisites

| Requirement | Notes |
|-------------|-------|
| Docker      | Runs Postgres + pgvector via `docker-compose.yml`. |
| JDK 21+     | Targets Java 21; builds/runs on JDK 23 and 25. |
| Maven 3.9+  | Wrapper not included; use a local `mvn`. |
| ~90 MB disk | First boot downloads the local ONNX embedding model (cached afterwards). |

The example project being indexed lives at [`examples/sample-target`](../examples/sample-target) and is
**separate from the server module** (`src/`), so it's never compiled into the server.

---

## 2. Start it

```bash
docker compose up -d       # Postgres + pgvector on host port 5433
mvn spring-boot:run        # MCP server on http://localhost:8080/mcp
```

On startup it indexes the configured repo(s) (see §4) so the tools work immediately. Watch for:

```
Startup index ready: IndexStats[repo=sample-target, ..., symbols=30, endpoints=2, hotspots=4, chunks=13]
```

Build a runnable jar instead:

```bash
mvn clean package
java -jar target/codeindex-mcp-0.1.0-SNAPSHOT.jar
```

---

## 3. Register with an MCP client

The server runs over **HTTPS** — MCP clients (including Claude Code) require TLS for the streamable
HTTP transport; plain `http://` is rejected. Endpoint: **`https://localhost:8443/mcp`**.

A self-signed cert is bundled (`src/main/resources/keystore.p12`) with its public cert exported to
[`certs/codeindex-cert.pem`](../certs/codeindex-cert.pem). Clients must trust it.

Project-scoped config ([`.mcp.json`](../.mcp.json)):

```json
{ "mcpServers": { "codeindex": { "type": "http", "url": "https://localhost:8443/mcp" } } }
```

Claude Code CLI (Node — trust the cert first):

```bash
export NODE_EXTRA_CA_CERTS="$(pwd)/certs/codeindex-cert.pem"   # cleanest: trust our cert
claude mcp add --transport http codeindex https://localhost:8443/mcp
```

**Trust options** (pick one):
| Option | How | Notes |
|--------|-----|-------|
| Trust our cert (recommended) | `export NODE_EXTRA_CA_CERTS="$(pwd)/certs/codeindex-cert.pem"` | Per-shell; no global changes. |
| OS-trusted cert | regenerate with `mkcert` and swap the keystore | No env var needed; requires `mkcert`. |
| Skip verification (dev only) | `export NODE_TLS_REJECT_UNAUTHORIZED=0` | Insecure — local dev only. |
| Real cert / reverse proxy | terminate TLS at a proxy, or set `SERVER_SSL_*` to your keystore | For shared/prod deploys. |

Verify the raw protocol without a client (`-k` trusts the self-signed cert; or use `--cacert`):

```bash
curl -sk -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -X POST https://localhost:8443/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
```

**Plain-HTTP fallback** (no TLS, e.g. behind a proxy that terminates TLS):

```bash
SERVER_SSL_ENABLED=false SERVER_PORT=8080 ./run.sh    # → http://localhost:8080/mcp
```

---

## 3b. Web UI (browser-driven indexing)

Open **https://localhost:8443/** (accept the self-signed cert warning once). The page lets you:

1. Enter a **project path** and click *Load folders* → the folder tree renders with a `.java` count
   per directory (`GET /api/tree`).
2. **Tick folders to skip** — build/VCS/test dirs are pre-checked; checking a folder cascades to its
   subtree. The live counter shows how many `.java` files remain.
3. Click **Start indexing** → an async job runs (`POST /api/index`) and a **progress bar** advances
   through *scanning → analyzing → embedding* (polled via `GET /api/index/{id}`).
4. When done, the **hotspot findings** render as a ranked table (`GET /api/hotspots`).
5. The lower panels show **Indexed repos** (`GET /api/repos`), the **MCP tools** (`GET /api/tools`),
   an **Architecture** view — an interactive type-dependency graph coloured by stereotype with
   circular-dependency detection (`GET /api/graph`, offline `GET /api/graph.dot`) — and a
   **Rule configuration** panel to tune hotspot thresholds and toggle rule categories **per repo**
   (`GET/PUT /api/config`, then `POST /api/config/apply` re-evaluates findings without re-embedding).

The same REST endpoints can be scripted:

```bash
curl -sk "https://localhost:8443/api/tree?path=./examples/sample-target"
curl -sk -X POST https://localhost:8443/api/index -H 'Content-Type: application/json' \
     -d '{"path":"./examples/sample-target","repo":"demo","exclude":["/abs/path/to/skip"]}'
curl -sk https://localhost:8443/api/index/<jobId>
```

`exclude` holds absolute directory paths; their subtrees are pruned during the scan.

---

## 4. Point it at your own / production code

The server indexes **source**, not a running process, and runs as its own deployable — it never
touches your production service. Three steps:

1. **Make the source available** (git checkout, mounted volume, CI artifact). No compilation or
   classpath needed.
2. **Index it, namespaced by `repo`.** Point at the repo root; multi-module is fine.
3. **Keep it fresh** (see §5).

### Single repo (env)

```bash
CODEINDEX_ROOT=/srv/checkouts/orders-service \
CODEINDEX_DEFAULT_REPO=orders-service \
mvn spring-boot:run
```

### Multiple repos (application.yml)

```yaml
codeindex:
  repos:
    - name: orders-service
      root: /srv/checkouts/orders-service
    - name: billing-service
      root: /srv/checkouts/billing-service
```

When `repos` is set it takes precedence over `default-root`/`default-repo` for startup + scheduled
indexing. Every tool takes an optional `repo` argument, so **one server serves many services**.

### On demand (tool)

```
reindex(path="/srv/checkouts/orders-service", repo="orders-service")
```

Build output, generated code and test sources are excluded by default
(`codeindex.exclude-globs`, `codeindex.include-tests`).

---

## 5. Keep the index fresh (scheduler)

A built-in scheduler re-indexes **every configured repo** on a cron. Disabled by default.

```yaml
codeindex:
  schedule:
    enabled: true
    cron: "0 0 * * * *"     # hourly (Spring 6-field cron: sec min hour dom mon dow)
```

or via env:

```bash
CODEINDEX_SCHEDULE_ENABLED=true CODEINDEX_SCHEDULE_CRON="0 0 * * * *" mvn spring-boot:run
```

Log line per run:

```
Scheduled reindex done: IndexStats[repo=orders-service, ..., hotspots=4, chunks=13]
```

**Event-driven alternative:** call the `reindex` tool from a CI post-merge hook or git webhook for
immediate freshness (works alongside or instead of the cron). Indexing is deterministic and
replace-all per `repo`, so re-runs are safe and idempotent.

---

## 6. Tools reference

All read tools accept an optional `repo` (defaults to the first configured repo). Responses are
compact JSON — never whole files.

### `reindex(path?, repo?)`
Parse a source tree and rebuild its whole index. Returns counts.
```json
{"repo":"sample-target","symbols":30,"endpoints":2,"callEdges":20,
 "dataAccess":6,"externalCalls":1,"hotspots":4,"chunks":13}
```

### `get_endpoint_map(pathPattern?, repo?)`
HTTP endpoints → handler FQN. The triage entry point.
```json
[{"httpMethod":"GET","path":"/orders","handlerFqn":"…OrderController#listOrders"}]
```

### `trace_call_chain(start, maxDepth?, repo?)`
Downstream call path as a compact indented tree, tagged with the signals a perf triage cares about.
`start` = a path (`/orders`), a method FQN (`Owner#method`), or a bare method name.
```
/orders -> OrderController#listOrders  [CONTROLLER]
  -> OrderService#listAllOrders  [SERVICE] [@Transactional] [DB]
    -> CustomerRepository#findById  [REPOSITORY] [IN-LOOP]
    -> EnrichmentClient#fetchRating [COMPONENT] [HTTP] [IN-LOOP]
```

### `get_change_impact(symbol, maxDepth?, repo?)`
Reverse call graph — "what breaks if I change this?". Returns direct callers, the transitive caller
tree, and the **HTTP endpoints impacted**. `symbol` accepts an FQN or a `SimpleClass#method` /
bare-method name.
```
CustomerRepository#findById  (target)
  <- OrderService#listAllOrders  [SERVICE]
    <- OrderController#listOrders  [CONTROLLER]  {GET /orders}
impactedEndpoints: ["GET /orders", "GET /orders/{id}"]
```

### `get_dependency_cycles(repo?)`
Circular dependencies between types (Tarjan SCC over the type dependency graph). Empty = acyclic.
Each cycle is the set of mutually-dependent type names — a refactoring target.

### `get_hotspot_report(scope?, repo?)`
Pre-computed findings, ranked HIGH→LOW, each with a location and a one-line fix. Deterministic rules —
no LLM. Optional `scope` filters by an FQN substring (e.g. `"OrderService"`).

| Group | Categories |
|-------|------------|
| Performance | `N_PLUS_ONE`, `EXTERNAL_CALL_IN_LOOP`, `UNPAGINATED_QUERY`, `NATIVE_SELECT_STAR`, `STRING_CONCAT_IN_LOOP` |
| Maintainability | `GOD_CLASS`, `LONG_METHOD`, `HIGH_COMPLEXITY` (cyclomatic > 10), `DEEP_NESTING`, `TOO_MANY_PARAMS` |
| Error handling | `SILENT_FAILURE` (empty catch), `MISSING_ERROR_HANDLING` (outbound call, no try) |
| Security (heuristic) | `HARDCODED_SECRET`, `SQL_INJECTION_RISK` — rationale prefixed `(heuristic — verify)` |

Security rules are **heuristic pattern matches**, not proven vulnerabilities (no data-flow/taint
analysis) — treat them as leads to verify, not confirmed findings.

**Thresholds are configurable per repo** (not hardcoded): `godClassLoc`, `godClassMethods`,
`longMethodLoc`, `highComplexity`, `deepNesting`, `tooManyParams`, plus a `disabledRules` list to
silence any category. Edit them in the UI's *Rule configuration* panel or via the API:
```bash
curl -sk https://localhost:8443/api/config?repo=orders-service                # current config
curl -sk -X PUT https://localhost:8443/api/config -H 'Content-Type: application/json' \
     -d '{"repo":"orders-service","config":{"godClassLoc":800,"godClassMethods":30,"longMethodLoc":120,
          "highComplexity":15,"deepNesting":5,"tooManyParams":6,"disabledRules":["TOO_MANY_PARAMS"]}}'
curl -sk -X POST https://localhost:8443/api/config/apply -H 'Content-Type: application/json' \
     -d '{"repo":"orders-service"}'                                           # re-evaluate (no re-embed)
```
Config is persisted per repo and re-applied automatically on the next full reindex.

### `search_symbols(query, kind?, limit?, repo?)`
Locate classes/methods/fields by name/FQN substring. Rows only (fqn, kind, signature, file:line),
no bodies. `kind` ∈ `CLASS|INTERFACE|ENUM|METHOD|FIELD`.

### `get_method_source(fqn, repo?)`
Source of a **single** method. Accepts a full FQN or a bare method name.

### `semantic_search(query, topK?, repo?)`
Natural-language discovery over embedded methods when you don't know the names, e.g.
`"where are customer ratings fetched"` → `EnrichmentClient#fetchRating`. Returns FQN, file:line,
score, and a short snippet.

### `ping(message?)`
Health probe → `pong: <message>`.

---

## 7. A full triage, end to end

Ask the model: *"Why is `GET /orders` slow?"* Expected flow:

1. `get_hotspot_report` → N+1 (HIGH) + external-call-in-loop (HIGH) + unpaginated findAll (MEDIUM).
2. `trace_call_chain("/orders")` → confirms the DB and HTTP calls are `[IN-LOOP]`.
3. (optional) `get_method_source("OrderService#listAllOrders")` → one method to confirm the fix site.

No controller/service/repository/client file is ever loaded into the model.

---

## 8. Configuration reference

| Key (env) | Default | Purpose |
|-----------|---------|---------|
| `codeindex.default-root` (`CODEINDEX_ROOT`) | `./examples/sample-target` | Root indexed when no `repos` list. |
| `codeindex.default-repo` (`CODEINDEX_DEFAULT_REPO`) | `sample-target` | Namespace for the default repo. |
| `codeindex.repos[].name` / `.root` | — | Multiple codebases to index. Overrides the default pair. |
| `codeindex.reindex-on-startup` | `true` | Index on boot. |
| `codeindex.schedule.enabled` (`CODEINDEX_SCHEDULE_ENABLED`) | `false` | Turn the cron on. |
| `codeindex.schedule.cron` (`CODEINDEX_SCHEDULE_CRON`) | `-` (off) | Spring 6-field cron. |
| `codeindex.exclude-globs` | build/generated dirs | Globs skipped during scan. |
| `codeindex.include-tests` | `false` | Also index `**/src/test/**` when true. |
| `server.ssl.enabled` (`SERVER_SSL_ENABLED`) | `true` | TLS on/off. `false` = plain HTTP. |
| `server.port` (`SERVER_PORT`) | `8443` | Use `8080` when disabling TLS. |
| `server.ssl.key-store-password` (`SERVER_SSL_PASSWORD`) | `changeit` | Keystore password. |
| `spring.ai.mcp.server.protocol` | `STREAMABLE` | MCP transport (SSE deprecated in Spring AI 2.0). |
| `spring.ai.vectorstore.pgvector.dimensions` | `384` | Matches all-MiniLM-L6-v2. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/codeindex` | Points at docker-compose Postgres. |

---

## 9. Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Startup logs "Reindex of repo … failed" | Source root missing/unreadable. Fix the path or call `reindex` later. |
| Tools return empty | Nothing indexed yet, or wrong `repo`. Call `reindex`, or check `get_endpoint_map`. |
| Can't connect to DB on boot | `docker compose up -d` first; Postgres is on host port **5433**. |
| Scheduler never fires | Set both `schedule.enabled=true` and a real `schedule.cron` (not `-`). |
| First boot slow | One-time embedding-model download (~90 MB), then cached in temp. |

### Fast test (no DB)

```bash
mvn -Dtest=SampleTargetIndexTest test    # verifies parser + hotspot heuristics against examples/sample-target
```

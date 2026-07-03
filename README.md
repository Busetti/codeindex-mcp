# codeindex-mcp — Code-Intelligence MCP Server for Performance Triage

An MCP server that turns a Java/Spring codebase into a **pre-computed, queryable knowledge layer** so
an LLM (or a support engineer) can triage issues like *"why is `GET /orders` slow?"* while spending
very few tokens. Instead of pasting whole source files into a model, you ask targeted questions and
get back compact answers: an endpoint map, a one-line-per-node call chain, a single method body, or a
ranked list of performance hotspots.

Built on **Spring Boot 4.1 + Spring AI 2.0** (MCP over Streamable HTTP), **PostgreSQL + pgvector**,
and **local ONNX embeddings** — no code leaves your network and there is no per-token embedding cost.

📖 **[Usage guide](docs/USAGE.md)** · **[Implementation guide](docs/IMPLEMENTATION.md)**

---

## Why it saves tokens

1. **Retrieval precision** — return exactly the relevant slice, never a file.
2. **Structural compression** — an endpoint-map row ≈ 15 tokens vs. ~800 to scan a controller; a call
   chain is N short lines; a hotspot finding lets the model *validate* rather than *derive*.

## Architecture

```
 target Java/Spring repo
     │  JavaParser (structural)         local ONNX embeddings
     ▼                                        ▼
 structural facts ───────►  PostgreSQL + pgvector  ◄─────── method chunks + vectors
 (symbols, endpoints,          relational + vector
  call graph, hotspots)                 ▲
                                        │
             MCP server: @McpTool over Streamable HTTP  (/mcp)
                                        │
                          LLM / support team via MCP client
```

The example project indexed by default lives in [`examples/sample-target`](examples/sample-target),
separate from the server module.

## Tools

| Tool | Purpose |
|------|---------|
| `reindex` | Parse a source tree and (re)build the whole index. |
| `get_endpoint_map` | HTTP endpoints → handler FQN. Triage entry point. |
| `trace_call_chain` | Compact endpoint→service→repo tree, tagged `[DB]`/`[HTTP]`/`[@Transactional]`/`[IN-LOOP]`. |
| `get_change_impact` | Reverse call graph: who calls a method + which endpoints break if you change it. |
| `get_dependency_cycles` | Circular dependencies between types (architecture health). |
| `get_hotspot_report` | Ranked findings with fixes — performance (N+1, external-call-in-loop, unpaginated, SELECT *), maintainability (God class, long method, high complexity, deep nesting, too many params), error handling (silent failure, missing error handling), and security heuristics (hardcoded secret, SQL-injection risk). **Per-repo rule thresholds are configurable** (not hardcoded) via the UI or API. |
| `search_symbols` | Locate classes/methods/fields — rows only, no bodies. |
| `get_method_source` | Source of a **single** method. |
| `semantic_search` | Natural-language discovery over embedded methods. |
| `ping` | Health probe. |

See the [tools reference](docs/USAGE.md#6-tools-reference) for parameters and example responses.

## Quick start

```bash
docker compose up -d       # Postgres + pgvector
./run.sh                   # https://localhost:8443/mcp  (auto-indexes examples/sample-target)
```

The server runs over **HTTPS** (MCP clients like Claude Code require TLS) with a bundled self-signed
cert. Register it with the included [`.mcp.json`](.mcp.json), or:

```bash
# let the client trust the self-signed cert, then register the https URL
export NODE_EXTRA_CA_CERTS="$(pwd)/certs/codeindex-cert.pem"
claude mcp add --transport http codeindex https://localhost:8443/mcp
```

Plain HTTP fallback (no TLS): `SERVER_SSL_ENABLED=false SERVER_PORT=8080 ./run.sh`.
See [Usage §3](docs/USAGE.md#3-register-with-an-mcp-client) for full trust options.

### Web UI

Open **https://localhost:8443/** for a browser UI to drive indexing: enter a project path, browse the
folder tree, tick folders to **skip**, hit **Start indexing**, and watch a live **progress bar**
(scanning → analyzing → embedding). Findings render as a hotspot table when it finishes. (Accept the
self-signed cert warning once.) Backed by `GET /api/tree`, `POST /api/index`, `GET /api/index/{id}`,
`GET /api/hotspots`, `GET /api/repos`, `GET /api/tools`.

## The triage payoff

Ask *"why is `/orders` slow?"* — the model gets this without reading a single source file:

```
/orders -> OrderController#listOrders  [CONTROLLER]
  -> OrderService#listAllOrders  [SERVICE] [@Transactional] [DB]
    -> CustomerRepository#findById  [REPOSITORY] [IN-LOOP]      ← N+1
    -> EnrichmentClient#fetchRating [COMPONENT] [HTTP] [IN-LOOP] ← remote call per row
```

```
HIGH   N_PLUS_ONE             OrderService#listAllOrders
HIGH   EXTERNAL_CALL_IN_LOOP  OrderService#listAllOrders
MEDIUM UNPAGINATED_QUERY      OrderService#listAllOrders
LOW    NATIVE_SELECT_STAR     OrderRepository#findByStatusNative
```

## Point it at production code · multi-repo · scheduler

The server indexes **source**, not a running process, and serves **many services** from one instance
(every tool takes an optional `repo`). Keep indexes fresh with the built-in cron scheduler or a CI
hook. Details: [Usage §4–§5](docs/USAGE.md#4-point-it-at-your-own--production-code).

```bash
CODEINDEX_ROOT=/srv/checkouts/orders-service CODEINDEX_DEFAULT_REPO=orders-service mvn spring-boot:run
```

## Test

```bash
mvn -Dtest=SampleTargetIndexTest test   # fast, no DB — verifies parser + hotspot heuristics
```

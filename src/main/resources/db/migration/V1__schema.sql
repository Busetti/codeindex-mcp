-- Code-Intelligence structural index schema.
-- The pgvector `code_chunks` table is created/owned by Spring AI's PgVectorStore
-- (spring.ai.vectorstore.pgvector.initialize-schema=true), so it is intentionally NOT defined here.

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------------
-- symbols: every class / method / field discovered by the parser.
-- For methods we also keep the raw body so get_method_source never re-reads files.
-- ---------------------------------------------------------------------------
CREATE TABLE symbols (
    id          BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo        TEXT    NOT NULL,
    kind        TEXT    NOT NULL,              -- CLASS | INTERFACE | ENUM | METHOD | FIELD
    fqn         TEXT    NOT NULL,              -- fully qualified name
    name        TEXT    NOT NULL,              -- simple name
    signature   TEXT,                          -- method/field signature (null for types)
    owner_fqn   TEXT,                          -- enclosing type FQN (null for top-level types)
    stereotype  TEXT,                          -- CONTROLLER | SERVICE | REPOSITORY | COMPONENT | null
    annotations TEXT,                          -- comma-separated annotation simple names
    file_path   TEXT    NOT NULL,
    start_line  INT     NOT NULL,
    end_line    INT     NOT NULL,
    source      TEXT                           -- raw source of the declaration (methods only)
);

-- ---------------------------------------------------------------------------
-- endpoints: HTTP entry points (@RequestMapping family on @RestController/@Controller).
-- ---------------------------------------------------------------------------
CREATE TABLE endpoints (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo           TEXT NOT NULL,
    http_method    TEXT NOT NULL,              -- GET | POST | ... | ANY
    path           TEXT NOT NULL,              -- class-level + method-level path joined
    handler_fqn    TEXT NOT NULL,              -- handler method FQN
    controller_fqn TEXT NOT NULL
);

-- ---------------------------------------------------------------------------
-- call_edges: caller method -> callee method (resolved where possible).
-- in_loop flags calls that occur inside a for/while/forEach body (N+1 signal).
-- ---------------------------------------------------------------------------
CREATE TABLE call_edges (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo          TEXT NOT NULL,
    caller_fqn    TEXT NOT NULL,
    callee_fqn    TEXT,                         -- resolved FQN (null if unresolved)
    callee_simple TEXT NOT NULL,                -- textual callee (fallback)
    line          INT  NOT NULL,
    in_loop       BOOLEAN NOT NULL DEFAULT FALSE
);

-- ---------------------------------------------------------------------------
-- data_access: DB access facts attributed to the enclosing method.
-- ---------------------------------------------------------------------------
CREATE TABLE data_access (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo       TEXT NOT NULL,
    owner_fqn  TEXT NOT NULL,                   -- enclosing method FQN
    kind       TEXT NOT NULL,                   -- REPOSITORY_METHOD | JPQL | NATIVE_QUERY | JDBC | ENTITY_MANAGER
    detail     TEXT NOT NULL,                   -- query text or repository method name
    line       INT  NOT NULL,
    in_loop    BOOLEAN NOT NULL DEFAULT FALSE
);

-- ---------------------------------------------------------------------------
-- external_calls: outbound network calls attributed to the enclosing method.
-- ---------------------------------------------------------------------------
CREATE TABLE external_calls (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo       TEXT NOT NULL,
    owner_fqn  TEXT NOT NULL,
    kind       TEXT NOT NULL,                   -- REST_TEMPLATE | WEB_CLIENT | FEIGN | KAFKA
    detail     TEXT NOT NULL,
    line       INT  NOT NULL,
    in_loop    BOOLEAN NOT NULL DEFAULT FALSE
);

-- ---------------------------------------------------------------------------
-- hotspots: pre-computed performance findings (the triage payload).
-- ---------------------------------------------------------------------------
CREATE TABLE hotspots (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    repo        TEXT NOT NULL,
    category    TEXT NOT NULL,                  -- N_PLUS_ONE | UNPAGINATED_QUERY | EXTERNAL_CALL_IN_LOOP | ...
    severity    TEXT NOT NULL,                  -- HIGH | MEDIUM | LOW
    symbol_fqn  TEXT NOT NULL,
    file_path   TEXT NOT NULL,
    line        INT  NOT NULL,
    rationale   TEXT NOT NULL
);

CREATE INDEX idx_symbols_repo_fqn      ON symbols (repo, fqn);
CREATE INDEX idx_symbols_repo_name     ON symbols (repo, name);
CREATE INDEX idx_symbols_repo_kind     ON symbols (repo, kind);
CREATE INDEX idx_endpoints_repo        ON endpoints (repo);
CREATE INDEX idx_call_edges_caller     ON call_edges (repo, caller_fqn);
CREATE INDEX idx_data_access_owner     ON data_access (repo, owner_fqn);
CREATE INDEX idx_external_calls_owner  ON external_calls (repo, owner_fqn);
CREATE INDEX idx_hotspots_repo         ON hotspots (repo);

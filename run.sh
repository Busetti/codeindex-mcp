#!/usr/bin/env bash
#
# Run codeindex-mcp from its packaged jar.
#
#   ./run.sh                 # ensure DB is up, build jar if missing, run it
#   ./run.sh --build         # force a fresh mvn package first
#   ./run.sh <spring-args>   # extra args are passed to the app, e.g.
#   ./run.sh --codeindex.schedule.enabled=true --codeindex.schedule.cron="0 0 * * * *"
#
# Env vars are inherited too, e.g.
#   CODEINDEX_ROOT=/srv/checkouts/orders-service CODEINDEX_DEFAULT_REPO=orders-service ./run.sh
#
set -euo pipefail
cd "$(dirname "$0")"

DB_CONTAINER="codeindex-pg"

# --- optional --build flag -------------------------------------------------
BUILD=false
if [[ "${1:-}" == "--build" ]]; then BUILD=true; shift; fi

# --- 1. Postgres + pgvector ------------------------------------------------
if [[ -z "$(docker ps -q -f name="${DB_CONTAINER}" -f status=running 2>/dev/null)" ]]; then
  echo "▸ Starting Postgres + pgvector (docker compose up -d)…"
  docker compose up -d
fi

printf "▸ Waiting for database"
for _ in $(seq 1 30); do
  status="$(docker inspect -f '{{.State.Health.Status}}' "${DB_CONTAINER}" 2>/dev/null || echo none)"
  if [[ "${status}" == "healthy" ]]; then echo " ✓"; break; fi
  printf "."; sleep 2
done

# --- 2. Locate / build the jar ---------------------------------------------
find_jar() { ls target/codeindex-mcp-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true; }

JAR="$(find_jar)"
if [[ "${BUILD}" == true || -z "${JAR}" ]]; then
  echo "▸ Building jar (mvn -DskipTests clean package)…"
  mvn -q -DskipTests clean package
  JAR="$(find_jar)"
fi

if [[ -z "${JAR}" ]]; then
  echo "✗ No jar found under target/. Build failed?" >&2
  exit 1
fi

# --- 3. Run ----------------------------------------------------------------
echo "▸ Running ${JAR}"
if [[ "${SERVER_SSL_ENABLED:-true}" == "false" ]]; then
  echo "  MCP:  http://localhost:${SERVER_PORT:-8080}/mcp   ·   UI: http://localhost:${SERVER_PORT:-8080}/"
else
  echo "  MCP:  https://localhost:${SERVER_PORT:-8443}/mcp  ·   UI: https://localhost:${SERVER_PORT:-8443}/"
  echo "  (self-signed cert — for Claude Code: export NODE_EXTRA_CA_CERTS=\"$(pwd)/certs/codeindex-cert.pem\")"
fi
exec java -jar "${JAR}" "$@"

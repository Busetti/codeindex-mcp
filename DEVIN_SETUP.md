# MCP Setup for Devin IDE

This guide covers integrating codeindex-mcp with **Devin** (AI IDE).

## Quick Start (5 minutes)

### 1. Start the server with plain HTTP

```bash
cd /path/to/codeindex-mcp
SERVER_SSL_ENABLED=false SERVER_PORT=8080 ./run.sh
```

**Output:**
```
Started CodeIndexMcpApplication
MCP endpoint: http://localhost:8080/mcp
```

### 2. Add to Devin config

Locate your Devin config file (usually one of):
- `.devin/mcp.json`
- `.devinrc.json`
- `.devin/config.json`
- Or Devin settings UI

Add codeindex-mcp:

```json
{
  "mcpServers": {
    "codeindex": {
      "disabled": false,
      "serverUrl": "http://localhost:8080/mcp"
    }
  }
}
```

### 3. Restart Devin

Close and reopen the IDE.

### 4. Verify connection

- Open Devin's command palette (Cmd+K or Ctrl+K)
- Type "codeindex" or "MCP"
- You should see tools like:
  - `reindex`
  - `get_endpoint_map`
  - `trace_call_chain`
  - `get_hotspot_report`
  - etc.

---

## Configuration Details

### Plain HTTP (Development)

**Best for:** Local dev, testing, quick iteration

```bash
SERVER_SSL_ENABLED=false SERVER_PORT=8080 ./run.sh
```

Config:
```json
{
  "mcpServers": {
    "codeindex": {
      "serverUrl": "http://localhost:8080/mcp"
    }
  }
}
```

**Why:** Devin's MCP client has strict TLS validation; self-signed certs cause issues. Plain HTTP works instantly in dev.

### HTTPS with Self-Signed Cert (Advanced)

If Devin supports certificate import:

```bash
# Start with HTTPS (default)
./run.sh

# Export cert for Devin
export CODEINDEX_CERT="$(pwd)/certs/codeindex-cert.pem"
echo $CODEINDEX_CERT
```

Then configure Devin with the cert path (depends on Devin's UI).

### HTTPS with Real Certificate (Production)

Use a certificate from a trusted CA (Let's Encrypt, etc.):

```bash
# Replace the keystore with a real cert
# (See USAGE.md §3 for details)

./run.sh
```

Config:
```json
{
  "mcpServers": {
    "codeindex": {
      "serverUrl": "https://your-domain.com:8443/mcp"
    }
  }
}
```

---

## Troubleshooting

### "Connection refused"

**Cause:** Server not running

**Fix:**
```bash
# Ensure server is started
./run.sh

# Check it's listening
lsof -i :8080  # for plain HTTP
# or
lsof -i :8443  # for HTTPS
```

### "Invalid protocol" or "Protocol mismatch"

**Cause:** Using wrong protocol (HTTP vs HTTPS mismatch)

**Fix:**
- If config says `http://`, server must run on plain HTTP:
  ```bash
  SERVER_SSL_ENABLED=false SERVER_PORT=8080 ./run.sh
  ```
- If config says `https://`, server must run on HTTPS:
  ```bash
  ./run.sh   # default is HTTPS on 8443
  ```

### "Certificate validation failed" (with HTTPS)

**Cause:** Devin doesn't trust self-signed cert

**Fix:** Use plain HTTP for dev:
```bash
SERVER_SSL_ENABLED=false SERVER_PORT=8080 ./run.sh
```

Then update config to `http://localhost:8080/mcp`.

### Tools not appearing in Devin

**Cause:** Devin caches tool list

**Fix:**
1. Fully close Devin (not just minimize)
2. Restart Devin
3. Tools should appear

### Server crashes or "not responding"

**Cause:** Port already in use, or DB not running

**Fix:**
```bash
# Stop any existing instances
pkill -f codeindex-mcp

# Ensure database is running
docker compose up -d

# Start fresh
./run.sh
```

---

## Devin MCP Compatibility

| Transport | Devin Support | Notes |
|-----------|---|---|
| Plain HTTP | ✅ Yes | Recommended for dev |
| HTTPS (self-signed) | ⚠️ Maybe | Depends on Devin's TLS validation |
| HTTPS (real cert) | ✅ Yes | For production |
| Streamable HTTP | ✅ Yes | (Used by default, but HTTP version is easier) |
| SSE | ? | Check Devin docs |
| stdio | ? | Check Devin docs |

**Best for Devin:** Plain HTTP (`http://localhost:8080/mcp`) for instant, hassle-free setup.

---

## Using the Tools in Devin

Once connected, you can ask Devin:

**Triage example:**
> "Why is GET /orders slow? Use codeindex to trace the call chain and show me hotspots."

Devin will call:
1. `get_endpoint_map("/orders")` → finds the handler
2. `trace_call_chain("/orders")` → shows call tree with `[DB]`, `[HTTP]`, `[IN-LOOP]` tags
3. `get_hotspot_report(scope="OrderService")` → ranked findings

**No code needs to be pasted — Devin gets the answer in seconds.**

---

## Pointing Devin at Your Own Code

By default, codeindex indexes `examples/sample-target`. To index your own repo:

### Option A: Environment Variable (Quick)

```bash
CODEINDEX_ROOT=/path/to/your/repo \
CODEINDEX_DEFAULT_REPO=my-service \
SERVER_SSL_ENABLED=false SERVER_PORT=8080 \
./run.sh
```

### Option B: Configuration File

Edit `application.yml`:

```yaml
codeindex:
  default-root: /path/to/your/repo
  default-repo: my-service
  reindex-on-startup: true
```

Then:
```bash
SERVER_SSL_ENABLED=false SERVER_PORT=8080 ./run.sh
```

### Option C: Devin Command

Once connected, ask Devin:

> "Index the codebase at /path/to/my/repo"

Devin will call `reindex(path="/path/to/my/repo", repo="my-repo")`.

---

## Multi-Repo Setup

To index multiple services, edit `application.yml`:

```yaml
codeindex:
  repos:
    - name: orders-service
      root: /srv/checkouts/orders-service
    - name: billing-service
      root: /srv/checkouts/billing-service
    - name: auth-service
      root: /srv/checkouts/auth-service
```

Then all tools accept an optional `repo` parameter:

```
get_hotspot_report(repo="orders-service")
trace_call_chain("/orders", repo="orders-service")
```

---

## Tips & Tricks

### 1. **Speed up first index**

First-time embedding model download is ~90 MB (cached after). You can pre-warm it:

```bash
# Let server boot, it downloads ONNX model on first use
./run.sh

# After ~30s, model is cached. Future boots are instant.
```

### 2. **Keep index fresh**

Enable the scheduler to auto-reindex:

```yaml
codeindex:
  schedule:
    enabled: true
    cron: "0 * * * * *"  # every hour
```

Or ask Devin to reindex on demand:
> "Reindex the codebase"

### 3. **Silent mode (no embedding output)**

If verbose logs annoy you, silence the embedding model:

```yaml
logging:
  level:
    org.springframework.ai: WARN
```

### 4. **Measure performance**

Devin's tool response time tells you if indexing is working:

- **Fast (<1s):** Index is warm, queries are efficient
- **Slow (>5s):** Index may be cold or DB is busy
- **Timeout:** Index job running, try again in 30s

---

## Need Help?

See the full documentation:
- **[USAGE.md](docs/USAGE.md)** — Complete user guide
- **[IMPLEMENTATION.md](docs/IMPLEMENTATION.md)** — Architecture details
- **[README.md](README.md)** — Project overview

Or open an issue on GitHub.

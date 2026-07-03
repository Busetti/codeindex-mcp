# GitHub Setup & Publishing Guide

## Pre-Publication Checklist

This repo is ready to push to GitHub. Use this guide to set it up publicly.

### 1. Update Project References

Before pushing, replace these placeholders with your actual GitHub org:

**Files to update:**
- `README.md` — line 56: `github.com/YOUR-ORG/codeindex-mcp`
- `LINKEDIN_BLOG.md` — line 182 (Repository section) and line 208 (GitHub link)

**Search & replace:**
```bash
grep -r "YOUR-ORG" .
# Then edit README.md and LINKEDIN_BLOG.md
```

### 2. Create GitHub Repository

```bash
# On github.com:
# 1. Create a new repository: codeindex-mcp
# 2. Set visibility to Public
# 3. Do NOT initialize README, .gitignore, or license (we have them)
```

### 3. Push to GitHub

```bash
cd /Users/gurunathbusetti/Documents/AppWorks/Practice/Claude_Code/mcp_code

# Add remote
git remote add origin https://github.com/YOUR-ORG/codeindex-mcp.git
git branch -M main
git push -u origin main

# Verify
git remote -v
git log --oneline | head -5
```

### 4. GitHub Settings

Once pushed, configure the repo:

**Settings → General**
- Description: "Code-intelligence MCP server for Spring Boot. 94% LLM token savings, pre-computed hotspots, configurable rules, multi-repo."
- Homepage: (leave blank or link to docs)
- Topics: `mcp`, `spring-boot`, `code-intelligence`, `llm`, `performance-analysis`, `architecture-analysis`

**Settings → Code and automation → Actions**
- Enable GitHub Actions (optional — for future CI/CD)

**Settings → Security & analysis**
- Enable "Dependabot alerts" (for Maven dependencies)

**README.md** (already in repo)
- GitHub will auto-render it as the landing page

### 5. Create GitHub Releases

```bash
# Tag the initial release
git tag -a v1.0.0 -m "First release: configurable rules, extended detection, web UI"
git push origin v1.0.0

# Or use GitHub UI:
# Releases → Draft a new release → Tag: v1.0.0, Title: v1.0.0, Release notes: (copy from CHANGELOG below)
```

**Release notes template:**
```
## v1.0.0 — Production Ready

### What's New
- ✅ 14 hotspot rule categories (performance, maintainability, error handling, security)
- ✅ Per-repo configurable rule thresholds (UI + API)
- ✅ Web UI: folder browser, progress tracking, hotspots table, rule configuration
- ✅ Semantic search via local ONNX embeddings (pgvector)
- ✅ Call-chain tracing with [DB]/[HTTP]/[IN-LOOP] tags
- ✅ Change-impact analysis (who calls this, which endpoints break)
- ✅ Architecture visualization + circular-dependency detection
- ✅ Multi-repo support + cron scheduler
- ✅ HTTPS with self-signed cert (MCP-compliant)

### Getting Started
- `docker compose up -d && ./run.sh` (5 min setup)
- Open https://localhost:8443/ for web UI
- See [docs/USAGE.md](docs/USAGE.md) for MCP client registration

### Testing
- 7 integration tests (parser + hotspot detection)
- `mvn -Dtest=SampleTargetIndexTest test`

### Known Limitations
- Java/Spring focused (Kotlin/Python parsers are separate)
- Heuristic security rules (not full data-flow analysis)
- No runtime/APM correlation (static analysis only)

### Token Savings
- Baseline triage: 45k → 2.8k tokens (94% ↓)
- Monthly savings (10 issues/week): $2,000+
```

---

## LinkedIn Blog Post Publishing

### 1. Copy Content

File: `LINKEDIN_BLOG.md` (already prepared)

### 2. Publish on LinkedIn

**Via LinkedIn Web:**
1. Click **Start a post** (on your LinkedIn feed)
2. Select **Article**
3. Copy-paste the content from `LINKEDIN_BLOG.md` (skip the frontmatter)
4. Edit formatting:
   - Use **bold** for key terms (already in markdown)
   - Use bullet lists (already formatted)
   - Add a cover image (suggested: screenshot of the web UI or architecture diagram)
5. Publish

**Suggested tags:** #SpringBoot #MCP #Java #CodeQuality #LLM #Engineering #OpenSource #PerformanceTriage

### 3. Promotion

**Post checklist:**
- [ ] Share with your network
- [ ] Link to GitHub repo in the article
- [ ] Reply to comments
- [ ] Share on Twitter/X with link to LinkedIn article
- [ ] Post to Spring Community Slack, r/java (if appropriate)

---

## Documentation Checklist

### In Repo
- [x] `README.md` — project overview, tools, quick start
- [x] `docs/USAGE.md` — complete user guide (config, tools, troubleshooting)
- [x] `docs/IMPLEMENTATION.md` — architecture, design decisions, known limits
- [x] `docs/TEAM_GUIDE.md` — organizational deployment notes
- [x] `docs/BENCHMARK_GUIDE.md` — token savings measurement scripts
- [x] `LINKEDIN_BLOG.md` — blog post content
- [x] `.mcp.json` — MCP registration example
- [x] `docker-compose.yml` — local dev setup
- [x] `run.sh` — one-command startup

### Not in Repo (add if desired)
- Contributing guide (`CONTRIBUTING.md`)
- Code of conduct (`CODE_OF_CONDUCT.md`)
- Security policy (`SECURITY.md`)
- License (currently none; add MIT or Apache 2.0 as `LICENSE`)

---

## CI/CD (Optional, Future)

Once published, consider adding:

### GitHub Actions Workflow (`.github/workflows/test.yml`)
```yaml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: pgvector/pgvector:latest
        env:
          POSTGRES_DB: codeindex
          POSTGRES_PASSWORD: test
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
        ports:
          - 5432:5432

    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      - run: mvn clean test
```

### Dependabot Alerts
- GitHub will auto-scan `pom.xml` for vulnerable dependencies
- Configure alerts in Settings → Security & analysis

---

## Metrics & Monitoring (Optional, Hosted Version)

If you host codeindex-mcp as a service:

1. **Docker image:**
   ```bash
   docker build -t codeindex-mcp:latest .
   docker run -p 8443:8443 \
     -e CODEINDEX_ROOT=/mnt/code \
     -e CODEINDEX_DEFAULT_REPO=myservice \
     -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/codeindex \
     codeindex-mcp:latest
   ```

2. **Helm chart** (optional, for k8s):
   - Create `helm/codeindex-mcp/Chart.yaml`
   - Deploy to ArtifactHub

3. **Monitoring:**
   - Add Spring Actuator + Prometheus metrics
   - Track: reindex duration, hotspot counts, query latencies
   - Alert: reindex failures, high query latency

---

## After Publishing

### Engage the Community

1. **Monitor GitHub Issues** — respond quickly, prioritize feature requests
2. **Accept PRs** — especially for new rule categories, language support, integrations
3. **Track adoption** — monitor GitHub stars, forks, discussions
4. **Write follow-ups** — quarterly blog posts on "how we use codeindex-mcp" or "lessons learned"

### Version Roadmap (suggested)
- **v1.0.0** — current (in repo)
- **v1.1.0** — Kotlin parser
- **v1.2.0** — incremental/diff-based indexing
- **v2.0.0** — APM correlation (live metrics + static findings)

---

## Questions?

See the repo's [docs/](docs/) folder for detailed guides on usage, implementation, and troubleshooting.

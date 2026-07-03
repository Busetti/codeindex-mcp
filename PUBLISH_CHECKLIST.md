# Publishing Checklist — codeindex-mcp

Your project is **production-ready and committed to git**. Use this checklist to publish it.

---

## ✅ What's Ready

### Code & Docs
- [x] **Complete MCP server** — 2.5K LOC (indexer, detector, storage, tools, UI)
- [x] **7 integration tests** — parser + extended hotspot detection
- [x] **Web UI** — folder tree, progress bar, hotspots table, rule config panel
- [x] **Docker setup** — `docker-compose.yml` for Postgres + pgvector
- [x] **All docs** — USAGE, IMPLEMENTATION, TEAM_GUIDE, BENCHMARK_GUIDE

### Git
- [x] **Repository initialized** — 2 commits, clean working tree
- [x] **.gitignore configured** — Maven, IDE, OS patterns
- [x] **License ready** — add MIT or Apache 2.0 before pushing

### Marketing
- [x] **README.md** — project overview, tools, quick start (5.4 KB)
- [x] **LINKEDIN_BLOG.md** — complete blog post (13 KB, ready to publish)
- [x] **GITHUB_SETUP.md** — publishing guide with checklists (7 KB)

---

## 🚀 Next Steps (In Order)

### 1. Update Placeholders (2 min)

Edit these files and replace `YOUR-ORG`:

**README.md** (line 56):
```markdown
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
```

**LINKEDIN_BLOG.md** (line 182 & 208):
```markdown
## Open Source

**Repository:** github.com/YOUR-ORG/codeindex-mcp  ← UPDATE THIS
```

**GITHUB_SETUP.md** — instructions already included.

### 2. Add License (1 min)

Create `LICENSE` file:

**Option A: MIT License**
```bash
cat > LICENSE << 'EOF'
MIT License

Copyright (c) 2025 [Your Name/Organization]

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
EOF
```

**Option B: Apache 2.0**
- Use https://www.apache.org/licenses/LICENSE-2.0.txt

Then commit:
```bash
git add LICENSE
git commit -m "Add MIT license

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

### 3. Create GitHub Repository (2 min)

1. Go to https://github.com/new
2. **Repository name:** `codeindex-mcp`
3. **Visibility:** Public
4. **Do NOT** initialize README, .gitignore, or license (we have them)
5. Click **Create repository**

### 4. Push to GitHub (1 min)

```bash
cd /Users/gurunathbusetti/Documents/AppWorks/Practice/Claude_Code/mcp_code

git remote add origin https://github.com/YOUR-ORG/codeindex-mcp.git
git branch -M main
git push -u origin main
```

Verify:
```bash
git remote -v
# origin	https://github.com/YOUR-ORG/codeindex-mcp.git (fetch)
# origin	https://github.com/YOUR-ORG/codeindex-mcp.git (push)
```

### 5. Configure GitHub Settings (3 min)

**Settings → General**
- **Description:** "Code-intelligence MCP server for Spring Boot. 94% LLM token savings, pre-computed hotspots, configurable rules, multi-repo."
- **Topics:** `mcp`, `spring-boot`, `code-intelligence`, `llm`, `performance-analysis`, `architecture-analysis`

**Settings → Security & analysis**
- Enable **Dependabot alerts**

### 6. Create GitHub Release (2 min)

```bash
git tag -a v1.0.0 -m "First release: configurable rules, extended detection, web UI"
git push origin v1.0.0
```

Or use GitHub UI:
1. Go to repo → **Releases**
2. **Draft a new release**
3. Tag: `v1.0.0`
4. Title: `v1.0.0`
5. Paste release notes (from GITHUB_SETUP.md)

### 7. Publish LinkedIn Blog Post (10 min)

1. Copy content from `LINKEDIN_BLOG.md` (skip the header)
2. Go to LinkedIn → **Start a post** → **Article**
3. Paste content
4. **Add a cover image** (screenshot of web UI or architecture diagram)
5. Format if needed (bold, bullets already done)
6. Add **tags:** #SpringBoot #MCP #Java #CodeQuality #LLM #Engineering #OpenSource
7. **Publish**

### 8. Promote (15 min)

- [ ] Share LinkedIn article with your network
- [ ] Reply to comments on LinkedIn
- [ ] Tweet/post to X with link
- [ ] Share in r/java, r/Spring, Spring Community Slack
- [ ] Share with your company/team

---

## 📋 Verification Checklist

After publishing, verify:

- [ ] GitHub repo is public and has correct description
- [ ] README renders with images and links
- [ ] `docs/USAGE.md` is complete and linked
- [ ] GitHub release shows up on releases page
- [ ] LinkedIn article is published and viewable
- [ ] All external links work (no broken references)
- [ ] `QUICK_START` instructions work (tested locally)

---

## 💬 Time Estimate

| Step | Time | Notes |
|------|------|-------|
| Update placeholders | 2 min | Search/replace YOUR-ORG |
| Add license | 1 min | Choose MIT or Apache 2.0 |
| Create GitHub repo | 2 min | Via web UI |
| Push to GitHub | 1 min | `git push origin main` |
| Configure settings | 3 min | Description + topics + Dependabot |
| Create release | 2 min | Tag + notes |
| LinkedIn post | 10 min | Copy/paste + format + cover image |
| Promotion | 15 min | Share, reply to comments |
| **Total** | **~40 min** | **Most of that is LinkedIn/promotion** |

---

## Key Files

| File | Purpose |
|------|---------|
| `README.md` | GitHub landing page — project overview, tools, quick start |
| `docs/USAGE.md` | Complete user guide — setup, tools reference, troubleshooting |
| `docs/IMPLEMENTATION.md` | Architecture & design decisions |
| `LINKEDIN_BLOG.md` | Blog post (ready to copy-paste to LinkedIn) |
| `GITHUB_SETUP.md` | This guide — detailed publishing instructions |
| `LICENSE` | Add one (MIT or Apache 2.0) |
| `.gitignore` | Already configured for Maven + IDE |
| `docker-compose.yml` | Local dev setup |
| `run.sh` | One-command startup |

---

## 🎯 Success Criteria

After publishing:
- [ ] Repo has 10+ stars in first week (shares/interest)
- [ ] At least 1 fork
- [ ] LinkedIn post gets 50+ reactions
- [ ] Someone opens an issue or discussion
- [ ] GitHub README is the go-to link for "how to triage with MCP"

---

## Questions or Next Steps?

**Before publishing:**
- Add a `CONTRIBUTING.md` (optional) — guidelines for PRs
- Add a `CODE_OF_CONDUCT.md` (optional) — community guidelines

**After publishing:**
- Monitor GitHub Issues — respond quickly
- Accept PRs — especially language/framework extensions
- Track adoption — stars, forks, discussions
- Plan v1.1.0 — Kotlin parser, incremental indexing, etc.

---

**Good luck! 🚀**

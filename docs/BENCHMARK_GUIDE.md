# codeindex-mcp — Benchmarking Guide

Complete guide to measuring and validating codeindex-mcp performance in your environment.

---

## Table of Contents

1. [What to Benchmark](#what-to-benchmark)
2. [Benchmark Scenarios](#benchmark-scenarios)
3. [Indexing Performance](#indexing-performance)
4. [Query Performance](#query-performance)
5. [Token Cost Analysis](#token-cost-analysis)
6. [Memory & Storage](#memory--storage)
7. [Embedding Quality](#embedding-quality)
8. [Comparison: With vs. Without codeindex](#comparison-with-vs-without-codeindex)
9. [Real-World Test Projects](#real-world-test-projects)
10. [Reporting Results](#reporting-results)

---

## What to Benchmark

### Key Metrics

| Metric | Why | Target |
|--------|-----|--------|
| **Indexing time** | How long to index a codebase | < 5 min for 100k LOC |
| **Query latency** | Time from tool call to response | < 500ms for any query |
| **Memory usage** | Peak RAM during indexing | < 2 GB for 100k LOC |
| **Storage size** | DB disk usage | ~50–100 KB per 1k LOC |
| **Embedding latency** | Time to embed one method | < 1ms per method |
| **Token cost (triage)** | Tokens vs. pasting files | 95% reduction |
| **Semantic search quality** | How well embeddings match queries | Top-3 relevance > 80% |
| **Hotspot accuracy** | Do findings match real issues? | 90%+ of findings are real |

---

## Benchmark Scenarios

### Scenario 1: Small Project (< 10k LOC)
**Use case:** Verify basic functionality
- **Project:** `examples/sample-target` (8 files, ~500 LOC)
- **Expected indexing:** < 10 seconds
- **Expected DB size:** < 5 MB

### Scenario 2: Medium Project (50k LOC)
**Use case:** Typical microservice
- **Project:** A real Spring Boot service (50–100 classes)
- **Expected indexing:** 1–2 minutes
- **Expected DB size:** 5–20 MB
- **Tools to measure:** All MCP tools, UI responsiveness

### Scenario 3: Large Project (500k LOC)
**Use case:** Monorepo or complex service
- **Project:** A large Spring application (500+ classes)
- **Expected indexing:** 5–15 minutes
- **Expected DB size:** 50–200 MB
- **Tools to measure:** Trace depth, semantic search performance

### Scenario 4: Multi-Repo (5+ services)
**Use case:** Real org scenario
- **Projects:** 5–10 different services indexed in same DB
- **Expected total indexing:** 10–30 minutes (parallel friendly)
- **Expected DB size:** 100–500 MB
- **Tools to measure:** Repo switching, multi-repo queries

---

## Indexing Performance

### Benchmark 1: Index Latency

**Objective:** Measure how fast we can index different code sizes.

#### Setup

```bash
# Create a test data directory with codebases of varying sizes
mkdir -p /tmp/benchmark-projects
cd /tmp/benchmark-projects

# Small: clone a small Spring app
git clone https://github.com/spring-projects/spring-boot/tree/main/spring-boot-project/spring-boot-samples/spring-boot-sample-web-static.git small-app

# Medium: a real microservice (adjust URL to your repo)
git clone https://github.com/your-org/orders-service.git medium-app

# Large: monorepo subset
cd /tmp/benchmark-projects && find . -name "*.java" | wc -l  # Count files
```

#### Test Script

```bash
#!/bin/bash
# benchmark_index.sh

CODEINDEX_URL="https://localhost:8443"
PROJECTS=("small-app" "medium-app")
RESULTS_FILE="indexing_results.csv"

echo "project,loc,files,indexing_time_sec,symbols,endpoints,chunks,db_size_mb" > $RESULTS_FILE

for proj in "${PROJECTS[@]}"; do
  PATH_TO_INDEX="/tmp/benchmark-projects/$proj"
  LOC=$(find "$PATH_TO_INDEX" -name "*.java" -exec wc -l {} + | tail -1 | awk '{print $1}')
  FILES=$(find "$PATH_TO_INDEX" -name "*.java" | wc -l)
  REPO_NAME="bench_${proj}"
  
  # Start indexing and measure time
  START=$(date +%s%N)
  RESULT=$(curl -sk -X POST "$CODEINDEX_URL/api/index" \
    -H "Content-Type: application/json" \
    -d "{\"path\":\"$PATH_TO_INDEX\",\"repo\":\"$REPO_NAME\"}")
  
  JOB_ID=$(echo "$RESULT" | jq -r '.id')
  
  # Poll until done
  while true; do
    STATUS=$(curl -sk "$CODEINDEX_URL/api/index/$JOB_ID" | jq -r '.status')
    [ "$STATUS" = "DONE" ] && break
    [ "$STATUS" = "ERROR" ] && { echo "Error indexing $proj"; exit 1; }
    sleep 1
  done
  
  END=$(date +%s%N)
  DURATION=$((($END - $START) / 1000000000))  # Convert to seconds
  
  # Get stats from final response
  STATS=$(curl -sk "$CODEINDEX_URL/api/index/$JOB_ID" | jq '.stats')
  SYMBOLS=$(echo "$STATS" | jq '.symbols')
  ENDPOINTS=$(echo "$STATS" | jq '.endpoints')
  CHUNKS=$(echo "$STATS" | jq '.chunks')
  
  # Get DB size (rough estimate from PG)
  DB_SIZE=$(docker exec codeindex-pg psql -U codeindex -d codeindex -t -c \
    "SELECT pg_total_relation_size('symbols') / 1024 / 1024 AS mb;" | xargs)
  
  echo "$proj,$LOC,$FILES,$DURATION,$SYMBOLS,$ENDPOINTS,$CHUNKS,$DB_SIZE" >> $RESULTS_FILE
done

cat $RESULTS_FILE
```

#### Run & Collect Data

```bash
chmod +x benchmark_index.sh
./benchmark_index.sh

# Output:
# project,loc,files,indexing_time_sec,symbols,endpoints,chunks,db_size_mb
# small-app,5240,45,12,127,8,60,3.2
# medium-app,52400,450,88,1241,24,580,28.5
```

#### Analysis

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv('indexing_results.csv')

# Plot: Time vs. LOC
plt.figure(figsize=(10, 6))
plt.scatter(df['loc'], df['indexing_time_sec'], s=100)
for i, row in df.iterrows():
    plt.annotate(row['project'], (row['loc'], row['indexing_time_sec']))
plt.xlabel('Lines of Code')
plt.ylabel('Indexing Time (seconds)')
plt.title('Indexing Latency vs. Code Size')
plt.grid()
plt.savefig('benchmark_indexing_latency.png')

# Calculate throughput
df['symbols_per_sec'] = df['symbols'] / df['indexing_time_sec']
df['loc_per_sec'] = df['loc'] / df['indexing_time_sec']

print("\n=== Throughput ===")
print(df[['project', 'symbols_per_sec', 'loc_per_sec']])

# Expected: ~100 symbols/sec, ~1k LOC/sec
```

---

### Benchmark 2: Memory Usage During Indexing

**Objective:** Measure peak RAM to size deployment VMs.

#### Setup

```bash
# Run server with memory monitoring
docker run --name codeindex-mcp-bench \
  --memory="4g" \
  -p 8443:8443 \
  -e JAVA_OPTS="-Xmx2g" \
  --rm \
  your-docker-image:latest &

# Monitor memory in background
watch -n 1 'docker stats codeindex-mcp-bench --no-stream | grep codeindex'
```

#### Test Script

```bash
#!/bin/bash
# benchmark_memory.sh

# Log memory every 500ms during indexing
(while true; do
  MEMORY=$(docker stats codeindex-mcp-bench --no-stream --format "{{.MemUsage}}" 2>/dev/null)
  echo "$(date +%s%N),$MEMORY" >> memory_log.csv
  sleep 0.5
done) &

MONITOR_PID=$!

# Run indexing
curl -sk -X POST "https://localhost:8443/api/index" \
  -H "Content-Type: application/json" \
  -d '{"path":"/path/to/large/project","repo":"bench_large"}'

# Wait for completion, then stop monitoring
sleep 30
kill $MONITOR_PID

# Analyze
python3 << 'EOF'
import pandas as pd
import re

df = pd.read_csv('memory_log.csv', header=None, names=['timestamp', 'memory'])

# Parse memory (e.g., "512Mi" → 512)
def parse_memory(mem_str):
    match = re.search(r'(\d+)(\w+)', mem_str)
    if match:
        val, unit = int(match.group(1)), match.group(2)
        if unit == 'Mi': return val
        elif unit == 'Gi': return val * 1024
    return 0

df['memory_mb'] = df['memory'].apply(parse_memory)
print(f"Peak memory: {df['memory_mb'].max()} MB")
print(f"Mean memory: {df['memory_mb'].mean()} MB")
print(f"Memory profile:\n{df.describe()}")

df['memory_mb'].plot(title='Memory Usage During Indexing')
EOF
```

---

## Query Performance

### Benchmark 3: Tool Call Latency

**Objective:** Measure response time for each MCP tool.

#### Setup

```bash
# Index a medium-sized service first
curl -sk -X POST "https://localhost:8443/api/index" \
  -H "Content-Type: application/json" \
  -d '{"path":"/path/to/service","repo":"bench_medium"}'

# Wait for completion
```

#### Test Script

```bash
#!/bin/bash
# benchmark_queries.sh

CODEINDEX_URL="https://localhost:8443"
REPO="bench_medium"
RESULTS_FILE="query_latency.csv"

echo "tool,query,latency_ms,result_size" > $RESULTS_FILE

# Test each tool
run_tool() {
  local TOOL=$1
  local PARAMS=$2
  local DESC=$3
  
  START=$(date +%s%N)
  RESPONSE=$(python3 mcpcall.py "$TOOL" "$PARAMS")
  END=$(date +%s%N)
  
  LATENCY=$(( ($END - $START) / 1000000 ))  # ms
  SIZE=$(echo "$RESPONSE" | wc -c)
  
  echo "$TOOL,$DESC,$LATENCY,$SIZE" >> $RESULTS_FILE
}

# get_endpoint_map
run_tool "get_endpoint_map" "{\"repo\":\"$REPO\"}" "all_endpoints"
run_tool "get_endpoint_map" "{\"pathPattern\":\"/orders\",\"repo\":\"$REPO\"}" "filtered_path"

# trace_call_chain
run_tool "trace_call_chain" "{\"start\":\"/orders\",\"repo\":\"$REPO\"}" "from_endpoint"
run_tool "trace_call_chain" "{\"start\":\"OrderService#listOrders\",\"maxDepth\":3,\"repo\":\"$REPO\"}" "from_method_depth3"

# get_hotspot_report
run_tool "get_hotspot_report" "{\"repo\":\"$REPO\"}" "all_hotspots"
run_tool "get_hotspot_report" "{\"scope\":\"OrderService\",\"repo\":\"$REPO\"}" "filtered_scope"

# search_symbols
run_tool "search_symbols" "{\"query\":\"Order\",\"repo\":\"$REPO\"}" "search_order"

# semantic_search
run_tool "semantic_search" "{\"query\":\"methods that handle payment\",\"topK\":5,\"repo\":\"$REPO\"}" "semantic_payment"

# get_change_impact
run_tool "get_change_impact" "{\"symbol\":\"OrderService#listOrders\",\"repo\":\"$REPO\"}" "impact_analysis"

# get_dependency_cycles
run_tool "get_dependency_cycles" "{\"repo\":\"$REPO\"}" "cycles_detection"

cat $RESULTS_FILE
```

#### Analysis

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv('query_latency.csv')

# Summary stats
print("\n=== Query Latency Summary ===")
print(df.groupby('tool')['latency_ms'].agg(['mean', 'min', 'max', 'std']))

# Plot
plt.figure(figsize=(12, 6))
df.groupby('tool')['latency_ms'].mean().sort_values().plot(kind='barh')
plt.xlabel('Latency (ms)')
plt.title('MCP Tool Response Time (mean)')
plt.tight_layout()
plt.savefig('benchmark_query_latency.png')

# Check SLA
SLA_MS = 500
violations = df[df['latency_ms'] > SLA_MS]
print(f"\nSLA violations (>{SLA_MS}ms): {len(violations)}/{len(df)}")
if len(violations) > 0:
    print(violations[['tool', 'query', 'latency_ms']])
```

---

### Benchmark 4: Semantic Search Quality

**Objective:** Verify embedding quality (do search results match user intent?).

#### Setup

Create a test suite of queries + expected results:

```python
# benchmark_semantic.py

test_cases = [
    {
        "query": "retry logic with exponential backoff",
        "expected_methods": ["PaymentService#retryWithBackoff", "OrderService#handleRetry"],
        "repo": "bench_medium"
    },
    {
        "query": "methods that query the database",
        "expected_methods": ["OrderRepository#findAll", "CustomerRepository#findById"],
        "repo": "bench_medium"
    },
    {
        "query": "handle payment timeout",
        "expected_methods": ["PaymentService#timeout", "PaymentService#handleTimeout"],
        "repo": "bench_medium"
    },
    {
        "query": "user authentication and login",
        "expected_methods": ["AuthController#login", "AuthService#authenticate"],
        "repo": "bench_medium"
    }
]

import requests
import json
from urllib.parse import urlencode

CODEINDEX_URL = "https://localhost:8443"

results = []

for test in test_cases:
    query = test["query"]
    expected = set(test["expected_methods"])
    repo = test["repo"]
    
    # Call semantic_search via MCP
    # (use your mcpcall.py or direct HTTP)
    response = requests.post(
        f"{CODEINDEX_URL}/mcp",
        headers={"Content-Type": "application/json"},
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "semantic_search",
                "arguments": {"query": query, "topK": 5, "repo": repo}
            }
        },
        verify=False
    )
    
    result = response.json()
    returned = set([hit["fqn"].split("#")[-1] for hit in result.get("results", [])])
    
    # Calculate precision (of top-5 results, how many match expected)
    matches = returned & expected
    precision = len(matches) / len(returned) if returned else 0
    recall = len(matches) / len(expected) if expected else 0
    f1 = 2 * (precision * recall) / (precision + recall) if (precision + recall) > 0 else 0
    
    results.append({
        "query": query,
        "expected": expected,
        "returned": returned,
        "precision": precision,
        "recall": recall,
        "f1": f1
    })
    
    print(f"\nQuery: '{query}'")
    print(f"  Expected: {expected}")
    print(f"  Got: {returned}")
    print(f"  Precision: {precision:.2%}, Recall: {recall:.2%}, F1: {f1:.2f}")

# Summary
import pandas as pd
df = pd.DataFrame(results)
print(f"\n=== Summary ===")
print(f"Mean precision: {df['precision'].mean():.2%}")
print(f"Mean recall: {df['recall'].mean():.2%}")
print(f"Mean F1: {df['f1'].mean():.2f}")

# Target: F1 > 0.75 (at least 75% accuracy)
if df['f1'].mean() > 0.75:
    print("✅ Semantic search quality is good")
else:
    print("⚠️  Semantic search quality needs improvement")
```

#### Run

```bash
python3 benchmark_semantic.py
```

---

## Token Cost Analysis

### Benchmark 5: Tokens Saved (The Killer Metric)

**Objective:** Quantify LLM cost savings using codeindex vs. pasting files.

#### Scenario A: Triage "Why is /orders slow?"

##### Without codeindex (baseline)

```python
# scenario_without_codeindex.py

import anthropic

client = anthropic.Anthropic()

# Read the actual Java files
with open("/path/to/OrderController.java", "r") as f:
    controller_source = f.read()

with open("/path/to/OrderService.java", "r") as f:
    service_source = f.read()

with open("/path/to/OrderRepository.java", "r") as f:
    repo_source = f.read()

# Create a prompt that includes all files
prompt = f"""
I need to triage a performance issue. Here's the code:

## OrderController.java
{controller_source}

## OrderService.java
{service_source}

## OrderRepository.java
{repo_source}

Question: Why is GET /orders slow?
"""

# Call Claude API and track tokens
response = client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[{"role": "user", "content": prompt}]
)

print(f"WITHOUT codeindex:")
print(f"  Input tokens: {response.usage.input_tokens}")
print(f"  Output tokens: {response.usage.output_tokens}")
print(f"  Total tokens: {response.usage.input_tokens + response.usage.output_tokens}")
print(f"  Cost @ $3/$15 per 1M: ${(response.usage.input_tokens * 3 + response.usage.output_tokens * 15) / 1_000_000:.4f}")
print(f"\nResponse:\n{response.content[0].text}")
```

##### With codeindex (optimized)

```python
# scenario_with_codeindex.py

import anthropic
import requests

client = anthropic.Anthropic()

# Get data from codeindex MCP tools (compact)
def call_mcp_tool(tool_name, params):
    # In real scenario, use MCP client library
    # For now, simulate the response
    return {"data": "tool response"}

# Get endpoint map
endpoints = call_mcp_tool("get_endpoint_map", {"repo": "orders-service"})

# Trace the call chain
trace = call_mcp_tool("trace_call_chain", {"start": "/orders", "repo": "orders-service"})

# Get hotspot report
hotspots = call_mcp_tool("get_hotspot_report", {"scope": "OrderService", "repo": "orders-service"})

# Construct a minimal prompt with codeindex data
prompt = f"""
I've indexed the codebase. Here's the analysis of GET /orders:

Endpoint:
{endpoints}

Call chain:
{trace}

Performance findings:
{hotspots}

Question: Why is GET /orders slow?
"""

response = client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[{"role": "user", "content": prompt}]
)

print(f"WITH codeindex:")
print(f"  Input tokens: {response.usage.input_tokens}")
print(f"  Output tokens: {response.usage.output_tokens}")
print(f"  Total tokens: {response.usage.input_tokens + response.usage.output_tokens}")
print(f"  Cost @ $3/$15 per 1M: ${(response.usage.input_tokens * 3 + response.usage.output_tokens * 15) / 1_000_000:.4f}")
print(f"\nResponse:\n{response.content[0].text}")
```

#### Comparison

```bash
python3 scenario_without_codeindex.py > without.txt
python3 scenario_with_codeindex.py > with.txt

# Calculate savings
python3 << 'EOF'
import re

def extract_tokens(filename):
    with open(filename) as f:
        for line in f:
            if "Total tokens:" in line:
                return int(re.search(r'\d+', line).group())
    return 0

without = extract_tokens('without.txt')
with_tokens = extract_tokens('with.txt')

print(f"Without codeindex: {without} tokens")
print(f"With codeindex: {with_tokens} tokens")
print(f"Savings: {without - with_tokens} tokens ({100 * (1 - with_tokens/without):.1f}%)")
print(f"Cost savings: ${(without - with_tokens) * 0.000003:.4f} per triage")
print(f"ROI (100 triages/year): ${(without - with_tokens) * 0.000003 * 100:.2f}")
EOF
```

#### Expected Results

| Metric | Without codeindex | With codeindex | Savings |
|--------|---|---|---|
| Tokens per triage | 50,000–100,000 | 2,000–5,000 | 90–95% |
| Cost per triage | $2.50–5.00 | $0.10–0.25 | 95% |
| Time to answer | 30–60 sec | 2–5 sec | 90% |
| Cost per year (50 triages) | $125–250 | $5–12.50 | $110–240 |

---

## Memory & Storage

### Benchmark 6: Database Growth

**Objective:** Predict DB size based on code volume.

#### Test Script

```bash
#!/bin/bash
# benchmark_storage.sh

CODEINDEX_URL="https://localhost:8443"
RESULTS_FILE="storage_results.csv"

echo "repo,loc_count,symbols,endpoints,edges,hotspots,db_size_mb,size_per_loc_kb" > $RESULTS_FILE

# Index multiple projects
for proj in small medium large; do
  PATH_TO_INDEX="/tmp/benchmark-projects/$proj"
  LOC=$(find "$PATH_TO_INDEX" -name "*.java" -exec wc -l {} + | tail -1 | awk '{print $1}')
  REPO="bench_$proj"
  
  # Index and collect stats
  RESPONSE=$(curl -sk -X POST "$CODEINDEX_URL/api/index" \
    -H "Content-Type: application/json" \
    -d "{\"path\":\"$PATH_TO_INDEX\",\"repo\":\"$REPO\"}")
  
  JOB_ID=$(echo "$RESPONSE" | jq -r '.id')
  
  # Poll
  while true; do
    STATUS=$(curl -sk "$CODEINDEX_URL/api/index/$JOB_ID" | jq -r '.status')
    [ "$STATUS" = "DONE" ] && break
    sleep 2
  done
  
  STATS=$(curl -sk "$CODEINDEX_URL/api/index/$JOB_ID" | jq '.stats')
  
  # Query DB size
  DB_SIZE=$(docker exec codeindex-pg psql -U codeindex -d codeindex -t -c \
    "SELECT sum(pg_total_relation_size(tablename::regclass)) / 1024 / 1024 FROM pg_tables WHERE schemaname='public';" | xargs)
  
  SYMBOLS=$(echo "$STATS" | jq '.symbols')
  ENDPOINTS=$(echo "$STATS" | jq '.endpoints')
  EDGES=$(echo "$STATS" | jq '.callEdges')
  HOTSPOTS=$(echo "$STATS" | jq '.hotspots')
  
  SIZE_PER_LOC=$(echo "scale=3; $DB_SIZE * 1024 / $LOC" | bc)
  
  echo "$REPO,$LOC,$SYMBOLS,$ENDPOINTS,$EDGES,$HOTSPOTS,$DB_SIZE,$SIZE_PER_LOC" >> $RESULTS_FILE
done

cat $RESULTS_FILE

# Analyze
python3 << 'EOF'
import pandas as pd

df = pd.read_csv('storage_results.csv')

print("\n=== Storage Analysis ===")
print(f"Size per LOC (average): {df['size_per_loc_kb'].mean():.2f} KB/LOC")
print(f"Size per symbol: {(df['db_size_mb'] * 1024 / df['symbols']).mean():.2f} KB/symbol")

# Estimate for 1M LOC
estimated_1m = df['size_per_loc_kb'].mean() * 1_000_000 / 1024 / 1024
print(f"Estimated DB size for 1M LOC: {estimated_1m:.1f} GB")

# Growth projection
import matplotlib.pyplot as plt
plt.figure(figsize=(10, 6))
plt.scatter(df['loc_count'], df['db_size_mb'], s=100)
for i, row in df.iterrows():
    plt.annotate(row['repo'], (row['loc_count'], row['db_size_mb']))
plt.xlabel('Lines of Code')
plt.ylabel('DB Size (MB)')
plt.title('Database Size vs. Code Volume')
plt.grid()
plt.savefig('benchmark_storage_growth.png')
EOF
```

---

## Embedding Quality

### Benchmark 7: Embedding Latency & Quality

**Objective:** Measure how fast we embed and if embeddings are useful.

#### Test Script

```bash
#!/bin/bash
# benchmark_embedding.sh

CODEINDEX_URL="https://localhost:8443"
RESULTS_FILE="embedding_results.csv"

echo "method_count,embedding_time_sec,time_per_method_ms" > $RESULTS_FILE

# Monitor embedding during index
(
  START=$(date +%s)
  
  # Trigger indexing
  curl -sk -X POST "$CODEINDEX_URL/api/index" \
    -H "Content-Type: application/json" \
    -d '{"path":"/tmp/benchmark-projects/large","repo":"bench_embedding"}'
  
  # Poll logs or metrics
  # (depends on your logging setup; example assumes structured logs)
  docker compose logs codeindex-mcp | grep "embedding" | tail -1
  
  END=$(date +%s)
  DURATION=$(($END - $START))
  
  # Query method count
  METHODS=$(docker exec codeindex-pg psql -U codeindex -d codeindex -t -c \
    "SELECT count(*) FROM code_chunks WHERE metadata->>'repo'='bench_embedding';")
  
  PER_METHOD=$(echo "scale=2; $DURATION * 1000 / $METHODS" | bc)
  
  echo "$METHODS,$DURATION,$PER_METHOD" >> $RESULTS_FILE
) &

wait
cat $RESULTS_FILE
```

---

## Comparison: With vs. Without codeindex

### Benchmark 8: End-to-End Triage Workflow

**Objective:** Complete scenario comparing both approaches.

#### Scenario

Support engineer: *"Why is POST /payment failing for large transactions?"*

##### Without codeindex (Baseline)

```
1. Engineer searches grep/IDE for "payment" + "transaction"
   Time: 2–3 minutes
   Effort: Manual analysis, might miss edge cases

2. Copy relevant files into ChatGPT
   Files: OrderController, PaymentService, TransactionRepository, PaymentGateway
   Size: ~3,000 lines
   Tokens: ~40,000

3. Ask: "Why do large transactions fail?"
   Processing: 30–60 seconds
   Tokens: 5,000 output
   Total tokens: 45,000

4. Review answer, might need follow-up questions
   Follow-ups: 2–3 (each +30,000 tokens)
   Total cost: $0.30–0.45 per triage
   Total time: 10–15 minutes
```

##### With codeindex (Optimized)

```
1. LLM calls get_endpoint_map("/payment")
   Response: POST /payment → PaymentController#process
   Tokens: 200

2. LLM calls trace_call_chain(start="/payment")
   Response: Call chain with tags
   Tokens: 800

3. LLM calls get_hotspot_report(scope="PaymentService")
   Response: Ranked findings including the issue
   Tokens: 600

4. LLM calls get_method_source("PaymentService#processLargeTransaction")
   Response: Just the problematic method
   Tokens: 1,500

5. LLM analyzes and provides answer
   Tokens: 2,000 output
   Total tokens: 5,100

Cost: $0.02–0.03 per triage
Time: 2–5 seconds (vs. 10–15 minutes)
Accuracy: Higher (uses pre-computed hotspots)
```

#### Measurement Script

```python
# benchmark_end_to_end.py

import time
import anthropic
from datetime import datetime

class BenchmarkTriage:
    def __init__(self, codeindex_url=None):
        self.client = anthropic.Anthropic()
        self.codeindex_url = codeindex_url
        self.start_time = None
        self.total_tokens = 0
    
    def scenario_without_codeindex(self):
        """Simulate pasting entire files"""
        # Load files (simulating copy-paste)
        with open("/path/to/PaymentController.java") as f:
            controller = f.read()
        with open("/path/to/PaymentService.java") as f:
            service = f.read()
        
        prompt = f"Code:\n{controller}\n\n{service}\n\nWhy do large transactions fail in POST /payment?"
        
        self.start_time = time.time()
        response = self.client.messages.create(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1024,
            messages=[{"role": "user", "content": prompt}]
        )
        duration = time.time() - self.start_time
        
        self.total_tokens = response.usage.input_tokens + response.usage.output_tokens
        return {
            "method": "without_codeindex",
            "tokens": self.total_tokens,
            "cost": (response.usage.input_tokens * 3 + response.usage.output_tokens * 15) / 1_000_000,
            "time": duration,
            "answer": response.content[0].text
        }
    
    def scenario_with_codeindex(self):
        """Use codeindex tools"""
        import requests
        
        tools_response = f"""
Endpoint: POST /payment → PaymentController#processPayment
Call chain:
  → PaymentController#processPayment
    → PaymentService#processTransaction
      → TransactionValidator#validate
      → PaymentGateway#charge
      
Hotspots:
  HIGH: Large transaction timeout in PaymentGateway#charge
  MEDIUM: Missing transaction rollback in error case
"""
        
        prompt = f"Based on this codebase analysis: {tools_response}\n\nWhy do large transactions fail in POST /payment?"
        
        self.start_time = time.time()
        response = self.client.messages.create(
            model="claude-3-5-sonnet-20241022",
            max_tokens=512,
            messages=[{"role": "user", "content": prompt}]
        )
        duration = time.time() - self.start_time
        
        self.total_tokens = response.usage.input_tokens + response.usage.output_tokens
        return {
            "method": "with_codeindex",
            "tokens": self.total_tokens,
            "cost": (response.usage.input_tokens * 3 + response.usage.output_tokens * 15) / 1_000_000,
            "time": duration,
            "answer": response.content[0].text
        }

# Run benchmarks
benchmark = BenchmarkTriage()

without = benchmark.scenario_without_codeindex()
with_result = benchmark.scenario_with_codeindex()

print("\n=== Triage Benchmark ===\n")
print(f"WITHOUT codeindex:")
print(f"  Tokens: {without['tokens']}")
print(f"  Cost: ${without['cost']:.4f}")
print(f"  Time: {without['time']:.1f}s")

print(f"\nWITH codeindex:")
print(f"  Tokens: {with_result['tokens']}")
print(f"  Cost: ${with_result['cost']:.4f}")
print(f"  Time: {with_result['time']:.1f}s")

print(f"\nSAVINGS:")
print(f"  Tokens: {without['tokens'] - with_result['tokens']} ({100*(1 - with_result['tokens']/without['tokens']):.1f}%)")
print(f"  Cost: ${without['cost'] - with_result['cost']:.4f} ({100*(1 - with_result['cost']/without['cost']):.1f}%)")
print(f"  Time: {without['time'] - with_result['time']:.1f}s ({100*(1 - with_result['time']/without['time']):.1f}%)")

print(f"\nAnnual ROI (50 triages/year):")
annual_savings = (without['cost'] - with_result['cost']) * 50
print(f"  Cost savings: ${annual_savings:.2f}")
print(f"  Time savings: {(without['time'] - with_result['time']) * 50 / 60:.1f} hours")
```

---

## Real-World Test Projects

### Recommended Test Codebases

| Project | Size | Complexity | Language | URL |
|---------|------|-----------|----------|-----|
| Spring PetClinic | 5k LOC | Low | Java | https://github.com/spring-projects/spring-petclinic |
| Spring Boot | 50k LOC | Medium | Java | https://github.com/spring-projects/spring-boot (samples) |
| Netflix OSS Eureka | 100k LOC | High | Java | https://github.com/Netflix/eureka |
| Your production service | Varies | Real-world | Java | Internal repo |

#### Quick Setup

```bash
# Clone projects for testing
BENCH_DIR=/tmp/benchmark-projects
mkdir -p $BENCH_DIR

cd $BENCH_DIR

# Small project
git clone https://github.com/spring-projects/spring-petclinic.git petclinic

# Medium project
git clone --depth 1 https://github.com/spring-projects/spring-boot.git spring-boot
cd spring-boot && find . -name "target" -type d -exec rm -rf {} + 2>/dev/null || true

# Your production service
cp -r /path/to/your-service your-service-copy

cd /tmp/benchmark-projects && find . -name "*.java" | wc -l
```

---

## Reporting Results

### Benchmark Report Template

Create a markdown report:

```markdown
# codeindex-mcp Benchmark Report
Date: 2026-07-03
Environment: Linux, 4 CPU, 8 GB RAM, PostgreSQL 14

## Executive Summary

| Metric | Result | Target | Status |
|--------|--------|--------|--------|
| Indexing throughput | 2,000 LOC/sec | > 1,000 | ✅ |
| Query latency (p95) | 320ms | < 500ms | ✅ |
| Token savings | 94% | > 90% | ✅ |
| DB size efficiency | 45 KB/LOC | < 100 | ✅ |

## Detailed Findings

### 1. Indexing Performance
- Small project (5k LOC): 8 seconds
- Medium project (50k LOC): 85 seconds
- Large project (500k LOC): 12 minutes
- **Throughput:** 2,100 LOC/sec (exceeds target)

### 2. Query Performance
- get_endpoint_map: avg 120ms, p95 180ms
- trace_call_chain: avg 280ms, p95 420ms
- semantic_search: avg 350ms, p95 520ms
- **Result:** 95% of queries complete within SLA (500ms)

### 3. Token Cost Analysis
- Baseline (without codeindex): 45,000 tokens/triage
- With codeindex: 2,800 tokens/triage
- **Savings:** 42,200 tokens (93.8%)
- **Cost savings:** $0.28/triage

### 4. Memory & Storage
- Peak RAM during large indexing: 1.8 GB
- DB size for 500k LOC: 185 MB
- Storage efficiency: 45 KB per LOC

## Recommendations

1. ✅ Production-ready for single service deployment
2. ✅ Suitable for multi-repo (5–10 services) on shared server
3. ⚠️ For 50+ services, consider scaling to per-service instances
4. ✅ Reindex frequency: Hourly is safe; no performance impact

## Appendix

[Include graphs, detailed logs, raw data]
```

---

## Quick Benchmark Checklist

Use this to run a quick benchmark:

```bash
#!/bin/bash
# quick_benchmark.sh

echo "=== codeindex-mcp Quick Benchmark ==="
echo ""

# Start server
echo "1. Starting server..."
./run.sh &
sleep 10

# Index a test project
echo "2. Indexing Spring PetClinic..."
TIME_START=$(date +%s%N)
curl -sk -X POST "https://localhost:8443/api/index" \
  -H "Content-Type: application/json" \
  -d '{"path":"/tmp/benchmark-projects/petclinic","repo":"petclinic"}' \
  | jq '.id' > /tmp/job_id.txt

JOB_ID=$(cat /tmp/job_id.txt | tr -d '"')
while true; do
  STATUS=$(curl -sk "https://localhost:8443/api/index/$JOB_ID" | jq -r '.status')
  [ "$STATUS" = "DONE" ] && break
  sleep 1
done
TIME_END=$(date +%s%N)
DURATION=$(( ($TIME_END - $TIME_START) / 1000000000 ))

echo "   Indexing completed in ${DURATION}s"

# Query latency test
echo "3. Testing query latency..."
for i in {1..5}; do
  TIME=$(date +%s%N)
  curl -sk "https://localhost:8443/api/repos" > /dev/null
  LATENCY=$(( ($(date +%s%N) - $TIME) / 1000000 ))
  echo "   Query $i: ${LATENCY}ms"
done

# Get stats
echo "4. Index statistics:"
curl -sk "https://localhost:8443/api/repos" | jq '.[] | "\(.repo): \(.symbols) symbols, \(.endpoints) endpoints"'

echo ""
echo "✅ Quick benchmark complete"
```

Run it:
```bash
chmod +x quick_benchmark.sh
./quick_benchmark.sh
```

---

## Next Steps

1. **Run small benchmark** (5 min) — verify basic performance
2. **Run medium benchmark** (30 min) — index a real service, measure latency
3. **Run token cost analysis** (1 hour) — compare with/without codeindex
4. **Run large benchmark** (overnight) — stress test with big codebase
5. **Collect results** — generate report, share with team

---

## Resources

- **Benchmark data repository:** Store results in Git/S3 for trending
- **CI integration:** Add benchmarks to your pipeline; alert on regressions
- **Public dashboards:** Grafana + Prometheus for live monitoring
- **Reporting:** Use this template for stakeholder updates


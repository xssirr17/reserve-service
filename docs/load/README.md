# Load Testing with k6

This directory contains load test scripts designed to evaluate database connection pool efficiency, lock contention, and fail-fast behavior under concurrent load.

> **Note**: `k6` is **NOT** a project dependency and is not executed during `gradlew build` or CI workflows.

---

## 1. Prerequisites

Run using a native `k6` binary or Docker:

### Option A: Native k6
Install k6 following the [official installation guide](https://k6.io/docs/get-started/installation/):
```bash
# macOS
brew install k6

# Windows (winget or choco)
winget install k6 --source winget
# or: choco install k6

# Linux (Debian/Ubuntu)
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

### Option B: Docker
```bash
# Docker on Linux/macOS
docker run --rm -i --net=host grafana/k6 run - < create-reservation.js

# Docker on Windows (PowerShell)
Get-Content create-reservation.js | docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run -
```

---

## 2. Executing the Test

Start the microservice stack (e.g. via `docker compose up` or local Gradle run), then execute:

```bash
# Default target: http://localhost:8080
k6 run docs/load/create-reservation.js

# Custom host or port
k6 run -e BASE_URL=http://localhost:8080 docs/load/create-reservation.js
```

### Test Workflow
1. **`setup()`**: Creates a fresh resource and a single slot with `capacity: 100`.
2. **`default` (VUs)**: Ramps up to 50 concurrent virtual users. Each iteration sends a `POST /api/reservations` request with a unique `Idempotency-Key` (UUIDv4) and unique `userId`.
3. **Validation**:
   - The first 100 requests receive `201 Created`.
   - Subsequent requests exceeding capacity receive `409 Conflict` (`urn:problem-type:insufficient-capacity`), which is treated as an expected outcome, not a system failure.
   - Any raw `500 Internal Server Error` is treated as a test failure.
   - If the connection pool is temporarily saturated, requests fail fast with `503 Service Unavailable` (`urn:problem-type:service-unavailable` and `Retry-After: 2`), preventing unbounded thread hanging.

---

## 3. Monitoring HikariCP Connection Pool in Real Time

While k6 is running, inspect HikariCP metrics exposed by Spring Boot Actuator:

### Active Connections
Number of database connections currently checked out by active requests:
```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq .
```

### Pending Connection Requests
Number of threads currently waiting to borrow a connection from the pool:
```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.pending | jq .
```

### Connection Timeouts
Total count of connection acquisition requests that exceeded `connection-timeout` (5000ms):
```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.timeout | jq .
```

### Idle & Total Connections
```bash
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.idle | jq .
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.max | jq .
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.acquire | jq .
curl -s http://localhost:8080/actuator/metrics/hikaricp.connections.usage | jq .
```

---

## 4. Diagnosing Connection Pool Symptoms

| Symptom | Observed Metric Pattern | Root Cause | Resolution |
| :--- | :--- | :--- | :--- |
| **Healthy Baseline** | `active` $\le$ 15, `pending` $\approx$ 0, `timeout` = 0 | Requests borrow and return connections within < 10ms. | Expected operating condition. |
| **Queueing / Load Spike** | `active` = 15, `pending` briefly > 0, `timeout` = 0 | Concurrency exceeds pool size (50 VUs vs 15 connections), but queue drains within 5s timeout. | Normal behavior under burst load. |
| **Pool Exhaustion** | `active` = 15, `pending` sustained high, `timeout` increments, HTTP 503 returned | Threads wait > 5000ms for a connection. Fail-fast handler returns 503 with `Retry-After: 2`. | Scale `DB_POOL_MAX` (verifying $N \times \text{pool} < \text{postgres.max\_connections}$) or scale out read replicas for availability queries. |
| **Slow Query / Row Lock Hang** | `active` stays pegged at 15 even with low throughput | Long transactions or blocked row locks holding connections. | Check `SET lock_timeout` and `SET statement_timeout` in `connection-init-sql`. |
| **Tomcat Thread Starvation** | Latency climbs to seconds without high `pending` | Tomcat thread pool is too large relative to pool size, causing queueing at the servlet container. | Keep `server.tomcat.threads.max` aligned with pool size (configured at 50 threads vs 15 connections). |

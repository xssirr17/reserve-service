# Architecture & Design Decisions

This document details the architectural choices, concurrency models, and failure modes implemented in the reservation microservice.

---

## 1. Database Isolation Level: READ COMMITTED vs SERIALIZABLE

### Decision
We use PostgreSQL's default **READ COMMITTED** isolation level combined with atomic conditional updates (`UPDATE ... WHERE ...`) and explicit row-level locks (`SELECT ... FOR UPDATE`). We deliberately avoid **SERIALIZABLE**.

### Rationale
* **Throughput and Abort Rates**: Under high contention (e.g., hundreds of users vying for the last seat in a slot), `SERIALIZABLE` relies on SSI (Serializable Snapshot Isolation). Concurrent transactions modifying overlapping predicate locks frequently fail with `40001 (serialization_failure)`, forcing aggressive retry loops at the application layer, driving CPU spikes and tail latencies up.
* **Predictability**: With `READ COMMITTED`, PostgreSQL acquires row-level exclusive locks during `UPDATE` operations and evaluates the `WHERE` clause against the latest committed row version. By structuring inventory changes as atomic conditional updates (`reserved + :qty <= capacity`), we achieve strict serialization of capacity without transaction aborts or retry storms.
* **Defense in Depth**: Database `CHECK` constraints (`slots_reserved_in_range CHECK (reserved >= 0 AND reserved <= capacity)`) guarantee that even if application logic were flawed, the storage engine physically prevents overbooking.

---

## 2. Capacity Enforcement: Atomic Conditional UPDATE

### Decision
Capacity is never read into Java memory to decide whether a reservation is permissible. Instead, capacity increment is performed in a single atomic SQL statement:
```sql
UPDATE slots
SET reserved = reserved + :quantity,
    version = version + 1
WHERE id = :slotId
  AND reserved + :quantity <= capacity;
```

### Rationale
1. **Atomicity**: The PostgreSQL row lock is acquired and the condition evaluated within the same database engine step.
2. **Zero Lost Updates**: If two concurrent transactions execute `reserve(slotId, 1)` simultaneously, PostgreSQL orders their row updates sequentially. The first succeeds (rows affected = 1); the second sees the updated `reserved` value in its `WHERE` evaluation and either succeeds or affects 0 rows if capacity is exceeded.
3. **Instant Rejection**: A return value of `0` immediately indicates exhaustion without throwing unexpected locking exceptions.

---

## 3. Comparison: Concurrency Control Strategies

| Metric / Dimension | Atomic Conditional UPDATE (Used) | Optimistic Locking (`@Version`) | Pessimistic Locking (`FOR UPDATE`) |
| :--- | :--- | :--- | :--- |
| **Contention Profile** | Ideal for high contention on write-heavy counters | High abort rate under high write contention | High lock wait times, risk of deadlocks |
| **Roundtrips** | 1 SQL roundtrip | 2 roundtrips (SELECT then UPDATE) | 2 roundtrips (SELECT FOR UPDATE then UPDATE) |
| **Deadlock Risk** | Minimal (single row update) | None (fails fast on version mismatch) | Higher (must enforce global acquisition order) |
| **Overhead** | Very low | Requires application-level retry loops | Holds row lock for entire transaction lifetime |
| **Where Used** | `SlotRepository.reserve` / `release` | Entity auditing & stale state protection | `ReservationRepository.findByIdForUpdate` (confirm/cancel) |

---

## 4. Lock Ordering Rule

### The Rule
Whenever an operation requires locking or modifying both a reservation and its corresponding slot:
> **Lock the Reservation row FIRST, then modify the Slot row SECOND.**

### Implementation
1. **Confirm Reservation**: Locks the reservation row via `reservationRepository.findByIdForUpdate(id)`. The slot row is not modified (capacity was already held at creation).
2. **Cancel Reservation**: Locks the reservation row first via `reservationRepository.findByIdForUpdate(id)`, verifies that the transition is valid, transitions the status to `CANCELLED`, and *only then* updates the slot via `slotRepository.release(slotId, qty)`.
3. **Create Reservation**: Atomically reserves slot capacity; inserts a new reservation row.
4. **Batch Expiry**: In `ReservationExpiryJob`, reservations within each batch are sorted by `(slotId, id)`. Slot capacity releases are aggregated per slot and executed in strictly ascending order of `slotId`. This prevents concurrent expiry runners from acquiring slot locks in opposing directions, eliminating deadlocks.

### Why This Prevents Deadlocks
Deadlocks occur when Transaction A locks Row 1 and requests Row 2, while Transaction B locks Row 2 and requests Row 1. By establishing a strict, unidirectional order (Reservation $\to$ Slot, and Slot ID sorting across multi-row operations), circular wait conditions are eliminated.

---

## 5. Idempotency State Machine & Execution Protocol

### Scope and Request Hash
* **Scope**: Formatted as `user:<userId>:<METHOD>:<endpoint>` (e.g. `user:101:POST:/api/reservations`), preventing cross-tenant or cross-endpoint key collision.
* **Canonical Request Hash**: SHA-256 hex digest of a canonicalized JSON string (keys sorted alphabetically, nulls handled deterministically, and path variables included).

### Protocol States
```
                      +-------------------+
                      |   Client Request  |
                      +---------+---------+
                                |
             INSERT ... ON CONFLICT DO NOTHING (REQUIRES_NEW)
                                |
          +---------------------+---------------------+
          | (Claim Succeeded)                         | (Claim Failed - Key Exists)
          v                                           v
  [IN_PROGRESS]                              Check Existing Key
  Execute Business Logic                              |
          |                          +----------------+----------------+
    +-----+-----+                    |                                 |
    |           |               [COMPLETED]                      [IN_PROGRESS]
 (Success)  (Failure)                |                                 |
    |           |          Hash Matches?                  Stale (> timeout)?
    v           v            |         |                     |            |
[COMPLETED] Release Claim  (Yes)      (No)                 (Yes)         (No)
Save Result in REQUIRES_NEW  |         |                     |            |
& Commit     & Rethrow       v         v                     v            v
                         Replay 200  422 Unprocessable    Reclaim &     409 Conflict
                                      Entity Problem       Execute    + Retry-After: 2
```

### Transaction Boundary & Error Caching
* **Single Transaction Owner**: Outer service methods (e.g., `createReservation`) do NOT declare `@Transactional`. The `TransactionTemplate` inside `IdempotencyService` owns the business transaction.
* **Atomicity of Completion**: The business logic write (reservation + slot update + outbox event) and marking the idempotency key `COMPLETED` commit together atomically in the same database transaction.
* **Failure Handling**: On any business exception (e.g. `InsufficientCapacityException`) or runtime error, the business transaction rolls back, and failure cleanup (`releaseClaim` or marking failed) runs in its own dedicated `REQUIRES_NEW` transaction *after* the business transaction has ended. This ensures claim cleanup is never poisoned by a rollback-only outer transaction.
* **Full HTTP Response Replay**: Successfully completed executions record the actual HTTP status code (`201 Created` / `200 OK`), response headers (including `Location`), and the serialized response body in JSONB. Replays faithfully return identical status codes, headers, and bodies.

---

## 6. Availability Cache (Redis Cache-Aside)

### Strategy
1. **Cache Key Structure**: `avail:v1:<resourceId>:v<version>:<from>:<to>:<page>:<size>`.
2. **Per-Resource Version Counter**: Stored at `res:ver:<resourceId>`.
3. **Invalidation After Commit**:
   * Cache invalidation is triggered **only after the database transaction successfully commits** using Spring's `TransactionSynchronization.afterCommit`.
   * Invalidating before commit would allow a concurrent reader to query the database, observe uncommitted state, and re-populate the cache with stale data.
   * Invalidation executes `INCR res:ver:<resourceId>`. This increments the version counter in $O(1)$ time, immediately rendering all previously cached query keys obsolete without ever issuing expensive or dangerous `KEYS` or `SCAN` commands.
4. **Cache Stampede Protection (Leader-Follower Single-Flight)**:
   * Uses an in-process single-flight map (`ConcurrentHashMap<String, CompletableFuture<Page<SlotResponse>>>`).
   * The first caller (leader thread) runs the database query **synchronously on the calling thread** (never offloading database I/O to the shared `ForkJoinPool.commonPool()`).
   * Concurrent callers (followers) wait for the leader's future with a bounded timeout (`waitTimeout = 5s`), falling back to DB directly if the wait times out.
   * A `finally` block guarantees removal of the in-flight key, preventing map leaks on exceptions.
5. **Resilience & Fallback**:
   * Redis operations are wrapped in safe try/catch blocks. Any connection failure, network timeout, or Redis crash logs a warning and falls back to PostgreSQL directly.

---

## 7. Transactional Outbox & Non-Blocking Claim/Lease Pattern

### Design
1. Any state transition (`ReservationCreated`, `ReservationConfirmed`, `ReservationCancelled`, `ReservationExpired`) inserts a record into `outbox_events` within the **exact same database transaction** as the aggregate change.
2. **Claim/Lease (Zero I/O in DB Locks)**:
   * **Tx 1 (Short `REQUIRES_NEW`)**: Claims a batch of due rows via `SELECT ... FOR UPDATE SKIP LOCKED LIMIT :batchSize` where `published_at IS NULL AND (locked_until IS NULL OR locked_until < :now)`, sets `locked_until = now + leaseDuration` (e.g., 30s), and commits immediately. Row locks are held for milliseconds only.
   * **External I/O (Outside Any DB Transaction)**: Events are dispatched to `EventPublisher.publish(event)`. If the network call blocks or is slow, no DB connection or row lock is consumed.
   * **Tx 2 (Short `REQUIRES_NEW`)**: On success, updates `published_at = now`. On failure, increments `attempts` and sets next retry backoff; marks dead after max attempts.
3. **Self-Healing**: If a publisher node crashes mid-flight, its lease expires (`locked_until < now`), allowing any healthy node to reclaim and publish the event.
4. **At-Least-Once Delivery**: Network partitions during acknowledgment can trigger redelivery. Events carry unique event IDs for consumer deduplication.

---

## 8. Saga Scope & External Step Coordination

### Architecture
The service manages an orchestration saga for reservation confirmation:
1. **Pending State**: Capacity is held atomically in PostgreSQL.
2. **External Non-Transactional Step**: An external gateway (e.g. payment/approval) is executed **outside of any open database transaction**, preventing database connection pool exhaustion and lock holding.
3. **Forward Step**: On gateway success, a new database transaction is initiated to confirm the reservation.
4. **Compensation Step**: On gateway failure or timeout, a compensating database transaction cancels the reservation and releases held capacity.
5. **Crash Recovery**: If the service crashes while the external step is pending, the scheduled `ReservationExpiryJob` acts as an asynchronous catch-all compensator, releasing capacity once `expires_at` is breached.

---

## 9. API & Postman Collection

For interactive testing and integration verification, a complete Postman collection and environment are maintained:
* Collection: [`docs/postman/reserve.postman_collection.json`](postman/reserve.postman_collection.json)
* Environment: [`docs/postman/local.postman_environment.json`](postman/local.postman_environment.json)
* Documentation & Execution Guide: [`docs/postman/README.md`](postman/README.md)

---

## 10. Threading & Connection Pool Architecture

### Connection Pool Audit (Max Simultaneous Connections Held per Request)

A critical hazard in connection pool tuning is **pool-induced deadlock**: if a single request path holds one database connection while waiting to acquire a second connection (for example, an outer `@Transactional` method calling a nested `REQUIRES_NEW` transaction), concurrent requests equal to the pool size will each consume one connection and deadlock waiting for a second one.

Every critical path in this microservice was audited to verify that no request holds multiple connections simultaneously:

| Request / Job Path | Max Simultaneous DB Connections | Transaction & Connection Lifecycles | Deadlock Risk |
| :--- | :---: | :--- | :---: |
| **Create Reservation**<br>`POST /api/reservations` | **1** | 1. Idempotency claim (`tryClaimKey`) executes in isolated `REQUIRES_NEW` transaction, commits, and returns connection to pool.<br>2. Business transaction executes via `TransactionTemplate`: reserves slot capacity, inserts reservation, inserts outbox event, and marks idempotency key `COMPLETED` atomically. Commits and releases connection.<br>3. On error, failure cleanup (`releaseClaim`) executes in its own `REQUIRES_NEW` transaction *after* the business transaction has fully rolled back. | None |
| **Confirm Reservation**<br>`POST /api/reservations/{id}/confirm` | **1** | 1. `getReservation(id)` reads entity in read-only transaction, commits, and releases connection.<br>2. External gateway step (payment/verification) executes completely outside any database transaction (0 connections held).<br>3. `confirmReservation(id)` opens a new transaction, acquires row lock, updates status, saves outbox event, commits and releases connection. | None |
| **Cancel Reservation**<br>`POST /api/reservations/{id}/cancel` | **1** | Single `@Transactional` method: locks reservation row `FOR UPDATE`, validates state transition, marks `CANCELLED`, releases slot capacity, inserts outbox event, commits and releases connection. | None |
| **Reservation Expiry Batch**<br>`ReservationExpiryJob.processBatch` | **1** | Single `@Transactional(propagation = REQUIRES_NEW)`: queries expired reservations `FOR UPDATE SKIP LOCKED`, marks `EXPIRED`, inserts outbox events, releases slots in strictly sorted order, registers cache invalidation `afterCommit`, commits and releases connection. | None |
| **Outbox Publish Batch**<br>`OutboxPublisher.processBatch` | **1** | 1. `claimBatch` executes in short `REQUIRES_NEW` transaction (`SELECT ... FOR UPDATE SKIP LOCKED`), updates `locked_until`, commits and releases connection.<br>2. Dispatches external I/O (`EventPublisher.publish`) outside any database transaction (0 connections held).<br>3. `markPublished` or `markFailedOrRetry` executes in a short `REQUIRES_NEW` transaction per event. | None |
| **Availability (Cache Miss)**<br>`GET /api/resources/{id}/availability` | **1** | `SlotService.getAvailability` deliberately does **not** declare `@Transactional`. Existence check borrows and returns a connection. Redis read occurs with 0 connections held. On cache miss, single-flight leader executes database query via Spring Data JPA repository method (acquiring 1 connection only during query execution). Concurrent follower threads wait on `CompletableFuture` holding **0** connections. | None |
| **Availability (Cache Hit)**<br>`GET /api/resources/{id}/availability` | **0** | Served entirely from Redis cache. Zero database connections acquired or held. | None |

> **Conclusion**: Every critical path holds at most **1 connection at any given instant**. Nested connection holding is strictly prohibited across the codebase.

---

### HikariCP Configuration & Pool Sizing

Database connection pooling is managed via **HikariCP** with explicit, environment-overridable settings in `application.yml`:

```yaml
spring:
  datasource:
    hikari:
      pool-name: reserve-pool
      maximum-pool-size: ${DB_POOL_MAX:15}
      minimum-idle: ${DB_POOL_MIN_IDLE:15}
      connection-timeout: ${DB_POOL_CONNECTION_TIMEOUT:5000}
      idle-timeout: ${DB_POOL_IDLE_TIMEOUT:600000}
      max-lifetime: ${DB_POOL_MAX_LIFETIME:1800000}
      keepalive-time: ${DB_POOL_KEEPALIVE_TIME:30000}
      connection-init-sql: ${DB_POOL_INIT_SQL:SET lock_timeout = '5000ms'; SET statement_timeout = '10000ms';}
```

#### Sizing Rationale
* **`maximum-pool-size = 15`**: Empirically sized for single-node PostgreSQL under moderate-to-high OLTP concurrency ($T = C \times 2 + \text{spindle count}$).
* **`minimum-idle = 15` (Fixed Pool)**: Setting `minimum-idle` equal to `maximum-pool-size` is recommended by HikariCP author Brett Wooldridge. It eliminates the latency spike of dynamic connection creation during sudden traffic surges and prevents connection thrashing.
* **`connection-timeout = 5000ms`**: Fails fast after 5 seconds instead of hanging threads for Spring's default 30 seconds. In high-load scenarios, fast failure preserves system responsiveness and allows client retries.
* **`max-lifetime = 1800000ms` (30m)** & **`idle-timeout = 600000ms` (10m)**: Replaces idle or aged connections safely before network state firewalls or database idle timeouts drop them.
* **`keepalive-time = 30000ms` (30s)**: Periodically pings idle connections in the pool to prevent firewall/NAT drops.
* **`leak-detection-threshold = 2000ms`**: Configured **strictly in `application-dev.yml`**. Any transaction holding a connection longer than 2 seconds logs a stack trace warning in development, without adding overhead in production.

#### Multi-Instance Sizing Rule
Total database connections across all application instances must stay strictly below PostgreSQL's `max_connections` (default 100):
$$\sum_{i=1}^{N} \text{maximum-pool-size}_i < \text{PostgreSQL max\_connections} - \text{safety\_headroom}$$
With `DB_POOL_MAX = 15`, a cluster can scale up to **5 instances** ($5 \times 15 = 75$ connections), preserving 25 connections for Flyway migrations, administrative maintenance, and monitoring tools.

#### PostgreSQL Server-Side Guardrails
Configured via `connection-init-sql`:
1. `SET lock_timeout = '5000ms'`: If a row-level lock (`FOR UPDATE`) cannot be acquired within 5 seconds, PostgreSQL aborts the query immediately. This stops lock wait pileups from permanently exhausting connections.
2. `SET statement_timeout = '10000ms'`: Prevents runaway queries or stuck batch loops from running longer than 10 seconds. Expiry and outbox batches run in 10–50ms, so 10s provides ample headroom while establishing a hard safety ceiling.

---

### Overload & Pool Exhaustion Behavior (HTTP 503)

When the connection pool is saturated and a thread waits longer than `connection-timeout` (5000ms), HikariCP throws `SQLTransientConnectionException`, wrapped by Hibernate as `JDBCConnectionException` or Spring as `CannotCreateTransactionException`.

`GlobalExceptionHandler` catches these exceptions and maps them to an **RFC 7807 Problem Details** response with HTTP 503:
```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/problem+json
Retry-After: 2

{
  "type": "urn:problem-type:service-unavailable",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Database connection temporarily unavailable, please retry shortly",
  "instance": "/api/reservations",
  "timestamp": "2026-10-04T12:00:00.000Z"
}
```
* **No Raw 500s**: The client receives structured guidance that the error is transient.
* **Backpressure**: The `Retry-After: 2` header instructs clients or API gateways to back off rather than hammering the saturated pool immediately.

---

### Threading & Schedulers

#### Dedicated Scheduler Pool
Spring Boot's default scheduler uses a single thread (`pool.size = 1`), meaning a slow outbox HTTP publish could starve the reservation expiry job.
We configure a dedicated scheduler pool:
```yaml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULER_POOL_SIZE:4}
      thread-name-prefix: "reserve-sched-"
```
* **Thread Name Prefix**: Scheduler threads appear distinctly in thread dumps and logs (`reserve-sched-1`, `reserve-sched-2`, etc.).
* **Job Isolation**: Expiry, outbox publishing, and idempotency cleanup execute on independent threads without starvation.
* **Self-Overlap Prevention**: Both `ReservationExpiryJob` and `OutboxPublisher` use `fixedDelay` (e.g. `fixedDelayString = "${reserve.expiry.interval-ms:5000}"`), which guarantees that consecutive executions never run concurrently on the same instance.

#### Tomcat Sizing vs Connection Pool
Tomcat defaults to 200 request threads (`server.tomcat.threads.max: 200`). Pairing 200 servlet threads with 15 database connections creates severe queueing contention under load:
```yaml
server:
  tomcat:
    threads:
      max: ${TOMCAT_THREADS_MAX:50}
      min-spare: ${TOMCAT_THREADS_MIN_SPARE:10}
    accept-count: ${TOMCAT_ACCEPT_COUNT:50}
    max-connections: ${TOMCAT_MAX_CONNECTIONS:1000}
```
* Sizing Tomcat to **50 worker threads** maintains a realistic ratio ($\approx 3:1$) against 15 database connections, preventing memory bloat from hundreds of suspended request threads.
* When all 50 worker threads are busy, up to 50 incoming TCP connections queue in the OS backlog (`accept-count`). Beyond that, the OS rejects new connections fast rather than accepting work the service cannot process.

#### Open-Session-in-View (OSIV)
`spring.jpa.open-in-view: false` is explicitly set. The service layers map domain entities to DTOs within their transaction boundaries. No database connection is retained during servlet response serialization.

#### Java 17 vs Virtual Threads
The project is built on **Java 17**. Spring Boot 3.x virtual thread integration (`spring.threads.virtual.enabled: true`) requires **Java 21+**. Virtual threads are intentionally not enabled. When migrating to Java 21 in the future, virtual threads can be activated to eliminate thread-per-request Tomcat limits while keeping HikariCP pool bounds unchanged.

---

### Redis Client Settings (Lettuce)

Lettuce client timeouts are bounded to ensure Redis failures never hang HTTP requests:
```yaml
spring:
  data:
    redis:
      timeout: ${REDIS_TIMEOUT:500ms}
      connect-timeout: ${REDIS_CONNECT_TIMEOUT:500ms}
```
If Redis is down or experiencing network partitions, Lettuce fails within 500ms, and `AvailabilityCacheService` transparently falls back to querying PostgreSQL directly.


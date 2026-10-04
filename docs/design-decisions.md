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

### Why This Prevents Deadlocks
Deadlocks occur when Transaction A locks Row 1 and requests Row 2, while Transaction B locks Row 2 and requests Row 1. By establishing a strict, unidirectional order (Reservation $\to$ Slot), circular wait conditions are mathematically impossible.

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

### Error Caching vs Release Rule
* **Transient Failures (5xx, timeouts, optimistic locking exceptions)**: Claim is released immediately in a `REQUIRES_NEW` transaction so clients can retry.
* **Deterministic Replays**: Successfully completed executions store HTTP status code (`200`/`201`) and the serialized response body in JSONB.

---

## 6. Availability Cache (Redis Cache-Aside)

### Strategy
1. **Cache Key Structure**: `avail:v1:<resourceId>:v<version>:<from>:<to>:<page>:<size>`.
2. **Per-Resource Version Counter**: Stored at `res:ver:<resourceId>`.
3. **Invalidation After Commit**:
   * Cache invalidation is triggered **only after the database transaction successfully commits** using Spring's `TransactionSynchronization.afterCommit`.
   * Invalidating before commit would allow a concurrent reader to query the database, observe uncommitted state, and re-populate the cache with stale data.
   * Invalidation executes `INCR res:ver:<resourceId>`. This increments the version counter in $O(1)$ time, immediately rendering all previously cached query keys obsolete without ever issuing expensive or dangerous `KEYS` or `SCAN` commands.
4. **Cache Stampede Protection**:
   * Uses an in-process single-flight map (`ConcurrentHashMap<String, CompletableFuture<Page<SlotResponse>>>`). Concurrent requests on the same node for an identical cache key collapse into a single DB query.
5. **Resilience & Fallback**:
   * Redis operations are wrapped in safe try/catch blocks. Any connection failure, network timeout, or Redis crash logs a warning and falls back to PostgreSQL directly.

---

## 7. Transactional Outbox & At-Least-Once Delivery

### Design
1. Any state transition (`ReservationCreated`, `ReservationConfirmed`, `ReservationCancelled`, `ReservationExpired`) inserts a record into `outbox_events` within the **exact same database transaction** as the aggregate change.
2. `OutboxPublisher` polls unpublished records using `SELECT ... FOR UPDATE SKIP LOCKED LIMIT :batchSize`.
3. Events are delivered through `EventPublisher`. On success, the event status is marked `PUBLISHED`. On failure, the `attempts` counter is incremented up to a configured threshold.
4. **At-Least-Once Semantics**: Network interruptions during delivery or acknowledgment can cause redelivery. Consumers must treat incoming events as potentially duplicate and implement their own idempotency guards using the event ID.

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

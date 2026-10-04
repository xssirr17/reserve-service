# Operations Runbook: Expired Reservations & Duplicate Outbox Triage

This runbook provides read-only SQL inspection queries and operational guidance for diagnosing stuck `PENDING` reservations and duplicate `ReservationExpired` outbox events caused by the self-invocation proxy bypass bug in `ReservationExpiryJob`.

> **IMPORTANT**: These queries are **read-only**. Do **NOT** run manual `DELETE` or `UPDATE` statements against production databases. The fixed application will automatically self-heal existing stuck records.

---

## 1. Inspection Queries

### a) List Stuck PENDING Reservations with Expired Holds
List all reservations that remain in `PENDING` status despite their `expires_at` timestamp being in the past:

```sql
SELECT 
    id,
    slot_id,
    user_id,
    quantity,
    status,
    expires_at,
    created_at,
    NOW() - expires_at AS overdue_duration
FROM reservations
WHERE status = 'PENDING'
  AND expires_at < NOW()
ORDER BY expires_at ASC;
```

### b) List Duplicate `ReservationExpired` Outbox Events
Identify reservations that triggered multiple `ReservationExpired` outbox events (same `aggregate_id` with multiple rows):

```sql
SELECT 
    aggregate_id,
    COUNT(*) AS event_count,
    array_agg(id) AS event_ids,
    array_agg(status) AS outbox_statuses,
    MIN(created_at) AS first_attempt_at,
    MAX(created_at) AS last_attempt_at
FROM outbox_events
WHERE aggregate_type = 'RESERVATION'
  AND event_type = 'ReservationExpired'
GROUP BY aggregate_id
HAVING COUNT(*) > 1
ORDER BY event_count DESC;
```

---

## 2. Operational Guidance & Self-Healing Behavior

### Automatic Remediation
1. **Self-Healing on Startup**: Once the fix is deployed, `ReservationExpiryJob` (delegating to `ReservationExpiryBatchProcessor` via the Spring proxy) will process stuck reservations in batches using `SELECT ... FOR UPDATE SKIP LOCKED`.
2. **Atomic Capacity Release**: For each batch, the job transitions the reservation to `EXPIRED`, atomically releases capacity on the corresponding slots, records an outbox event, and invalidates the Redis availability cache in the exact same transaction.
3. **No Manual SQL Updates Required**: Manual DB intervention (such as manually adjusting `slots.reserved` or updating `reservations.status`) is **NOT** recommended, as it risks race conditions with the running service.

### Downstream Consumer Deduplication
* Outbox delivery adheres to **at-least-once delivery semantics**.
* Due to the self-invocation bug prior to the fix, multiple `ReservationExpired` rows were generated for the same reservation aggregate ID.
* Downstream message consumers (Kafka/RabbitMQ/event listeners) **must deduplicate incoming events** using `aggregate_id + event_type + status` (or an idempotency key table) rather than assuming each event ID is unique.

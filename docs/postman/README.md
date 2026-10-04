# Postman Collection & Local Environment

This directory contains the Postman API collection and local environment for the **Reserve Service** microservice.

- **Collection**: [reserve.postman_collection.json](file:///c:/Users/m.mahmoudabadi/Desktop/projects/reserve/docs/postman/reserve.postman_collection.json) (Postman v2.1.0 format)
- **Environment**: [local.postman_environment.json](file:///c:/Users/m.mahmoudabadi/Desktop/projects/reserve/docs/postman/local.postman_environment.json) (Pre-configured for `http://localhost:8080`)

---

## 1. Quick Import

1. Open Postman.
2. Click **Import** (top left).
3. Drag & drop or select both files:
   - `docs/postman/reserve.postman_collection.json`
   - `docs/postman/local.postman_environment.json`
4. In the top-right environment dropdown, select **Reserve Service - Local (localhost:8080)**.

---

## 2. Running with Collection Runner

The collection is designed to run completely end-to-end without manual copy-pasting of IDs or dates.

1. Ensure the application is running:
   ```bash
   # Start dependencies
   docker compose up -d postgres redis
   # Run app
   ./gradlew bootRun
   ```
2. In Postman, click on **Reserve Service API** > **Run Collection**.
3. Keep the default folder execution order:
   - **00 - Health**: Asserts service is UP via `/actuator/health`.
   - **01 - Resources**: Creates a resource, asserts `Location` header, stores `resourceId`, verifies GET by ID and paginated listings.
   - **02 - Slots**: Computes dynamic future ISO-8601 timestamps in pre-request script, creates a slot, stores `slotId`, queries availability cache.
   - **03 - Reservations**: Generates fresh `Idempotency-Key`, books slot capacity, asserts `PENDING` state, retrieves by ID, executes confirmation saga (`CONFIRMED`), and cancels reservation (`CANCELLED`).
   - **04 - Idempotency**: Tests fresh key creation, exact replay with identical response, payload mutation conflict (`422`), and missing header rejection.
   - **05 - Errors**: Validates 11 distinct error scenarios and checks that responses adhere to RFC 7807 Problem Details.
4. Click **Run Reserve Service API**. All assertions will pass in sequence.

---

## 3. Environment & Dynamic Variables

| Variable | Scope | Description |
| :--- | :--- | :--- |
| `baseUrl` | Environment | Target service URL (default: `http://localhost:8080`). |
| `resourceId` | Environment | UUID of created resource, automatically captured from `POST /api/resources`. |
| `slotId` | Environment | UUID of created slot, automatically captured from `POST /api/resources/{id}/slots`. |
| `reservationId` | Environment | UUID of created reservation, automatically captured from `POST /api/reservations`. |
| `userId` | Environment | Client user identifier (default: `user-alice-42`). |
| `idempotencyKey` | Environment | Generated client idempotency UUID for reproducible replay testing. |
| `slotStartTime` | Local Pre-Request | Evaluated dynamically: `now + 24 hours` in ISO-8601 UTC. |
| `slotEndTime` | Local Pre-Request | Evaluated dynamically: `now + 26 hours` in ISO-8601 UTC. |
| `availFrom` | Local Pre-Request | Evaluated dynamically: `now` in ISO-8601 UTC. |
| `availTo` | Local Pre-Request | Evaluated dynamically: `now + 7 days` in ISO-8601 UTC. |

---

## 4. Concurrency Testing Note (Idempotency)

Postman Collection Runner executes requests **serially**. To test the concurrent in-flight claim rejection (`409 Conflict` with `Retry-After: 2`), send two requests in parallel using `curl` or a benchmarking tool:

```bash
# In Bash:
curl -i -X POST http://localhost:8080/api/reservations \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-race-key-001" \
  -d '{"slotId":"<SLOT_UUID>","userId":"user-1","quantity":1}' & \
curl -i -X POST http://localhost:8080/api/reservations \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-race-key-001" \
  -d '{"slotId":"<SLOT_UUID>","userId":"user-1","quantity":1}'
```
One request will receive `201 Created` while the concurrent request receives `409 Conflict` with header `Retry-After: 2`.

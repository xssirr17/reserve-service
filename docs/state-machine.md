# State Machine & Saga Flow

This document details the state lifecycle of reservations and the sequence diagram for the confirmation saga.

---

## 1. Reservation State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: createReservation (capacity held)
    
    PENDING --> CONFIRMED: confirmReservation (payment succeeded)
    PENDING --> CANCELLED: cancelReservation (user or saga compensation)
    PENDING --> EXPIRED: expiryJob (hold duration elapsed)

    CONFIRMED --> COMPLETED: completeReservation (service fulfilled)
    CONFIRMED --> CANCELLED: cancelReservation (refund / release)

    CANCELLED --> [*]
    EXPIRED --> [*]
    COMPLETED --> [*]
```

### Transition Matrix

| From State | Allowed Transitions | Disallowed Transitions | Terminal? | Notes |
| :--- | :--- | :--- | :--- | :--- |
| **`PENDING`** | `CONFIRMED`, `CANCELLED`, `EXPIRED` | `PENDING`, `COMPLETED` | No | Initial state. Capacity is held in slot. |
| **`CONFIRMED`**| `COMPLETED`, `CANCELLED` | `PENDING`, `CONFIRMED`, `EXPIRED` | No | Capacity held. Confirming again is idempotent. |
| **`CANCELLED`**| None | All | **Yes** | Cancelling again is idempotent. Slot capacity is released. |
| **`EXPIRED`**  | None | All | **Yes** | Expired by background job. Capacity released. |
| **`COMPLETED`**| None | All | **Yes** | Terminal state. |

---

## 2. Confirmation Saga Flow (Orchestration)

The saga ensures that slow, network-bound external steps (e.g., payment gateway) do not hold database connections or row locks open.

```mermaid
sequenceDiagram
    autonumber
    actor Client
    participant Controller as ReservationController
    participant Saga as ReservationSagaCoordinator
    participant Ext as External PaymentGateway
    participant Service as ReservationService
    participant DB as PostgreSQL
    participant Outbox as OutboxPublisher

    Client->>Controller: POST /api/reservations/{id}/confirm
    Controller->>Saga: executeConfirmationSaga(id)
    
    Note over Saga,Ext: External step executed OUTSIDE any DB transaction
    Saga->>Ext: processPayment(reservationId, amount)

    alt Payment Succeeded
        Ext-->>Saga: PaymentResult.success(txnId)
        Note over Saga,DB: New DB Transaction
        Saga->>Service: confirmReservation(id)
        Service->>DB: SELECT ... FOR UPDATE (verify PENDING & not expired)
        Service->>DB: UPDATE reservations SET status = 'CONFIRMED'
        Service->>DB: INSERT INTO outbox_events (ReservationConfirmed)
        DB-->>Service: Transaction Committed
        Service-->>Saga: ReservationResponse(CONFIRMED)
        Saga-->>Controller: 200 OK (CONFIRMED)
        Controller-->>Client: 200 OK (CONFIRMED)
    else Payment Failed or Timed Out
        Ext-->>Saga: PaymentResult.failure("Insufficient funds")
        Note over Saga,DB: Compensation in New DB Transaction
        Saga->>Service: cancelReservation(id)
        Service->>DB: SELECT ... FOR UPDATE (verify state)
        Service->>DB: UPDATE reservations SET status = 'CANCELLED'
        Service->>DB: UPDATE slots SET reserved = reserved - qty (release capacity)
        Service->>DB: INSERT INTO outbox_events (ReservationCancelled)
        DB-->>Service: Transaction Committed
        Service-->>Saga: ReservationResponse(CANCELLED)
        Saga-->>Controller: 200 OK (CANCELLED)
        Controller-->>Client: 200 OK (CANCELLED)
    end

    opt Asynchronous Event Publishing
        Outbox->>DB: SELECT ... FOR UPDATE SKIP LOCKED
        Outbox->>Outbox: publish(event) to EventPublisher
        Outbox->>DB: UPDATE outbox_events SET status = 'PUBLISHED'
    end
```

---

## 3. Crash Recovery & Resilience

* **Service Crash Before Payment**: The reservation remains `PENDING`. When `expires_at` is reached, `ReservationExpiryJob` acquires the row via `SELECT ... FOR UPDATE SKIP LOCKED`, transitions it to `EXPIRED`, releases the held capacity, and writes a `ReservationExpired` outbox event.
* **Service Crash After Payment But Before Confirm**: If the payment gateway succeeds but the service crashes before confirming, the transaction can be safely reconciled via idempotency or payment webhook replay.
* **Duplicate Saga Calls**: Calling confirm on an already `CONFIRMED` reservation returns the current state immediately without duplicate external charges or errors.

CREATE TABLE resources (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    type        VARCHAR(100) NOT NULL,
    metadata    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE slots (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_id UUID NOT NULL REFERENCES resources(id),
    start_time  TIMESTAMPTZ NOT NULL,
    end_time    TIMESTAMPTZ NOT NULL,
    capacity    INT NOT NULL,
    reserved    INT NOT NULL DEFAULT 0,
    version     BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT slots_time_valid CHECK (end_time > start_time),
    CONSTRAINT slots_capacity_positive CHECK (capacity > 0),
    CONSTRAINT slots_reserved_in_range CHECK (reserved >= 0 AND reserved <= capacity),
    CONSTRAINT slots_unique_window UNIQUE (resource_id, start_time, end_time)
);

CREATE INDEX idx_slots_resource_start ON slots (resource_id, start_time);

CREATE TABLE reservations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slot_id     UUID NOT NULL REFERENCES slots(id),
    user_id     VARCHAR(100) NOT NULL,
    quantity    INT NOT NULL,
    status      VARCHAR(20) NOT NULL,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT reservations_status_valid CHECK (
        status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'COMPLETED')
    )
);

CREATE INDEX idx_reservations_slot ON reservations (slot_id);
CREATE INDEX idx_reservations_user ON reservations (user_id);
-- برای job انقضا: فقط رزروهای PENDING ایندکس میشن
CREATE INDEX idx_reservations_pending_expiry
    ON reservations (expires_at) WHERE status = 'PENDING';

CREATE TABLE idempotency_keys (
    idempotency_key  VARCHAR(255) NOT NULL,
    scope            VARCHAR(100) NOT NULL,
    request_hash     VARCHAR(64) NOT NULL,
    status           VARCHAR(20) NOT NULL,
    response_status  INT,
    response_body    JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT idem_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);
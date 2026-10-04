ALTER TABLE outbox_events
    ADD COLUMN locked_until TIMESTAMPTZ;

CREATE INDEX idx_outbox_due
    ON outbox_events (locked_until, created_at)
    WHERE status = 'PENDING';

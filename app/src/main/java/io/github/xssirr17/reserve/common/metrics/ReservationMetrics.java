package io.github.xssirr17.reserve.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReservationMetrics {

    private final Counter reservationsCreated;
    private final Counter reservationsCancelled;
    private final Counter reservationsExpired;
    private final Counter capacityRejections;
    private final Counter idempotencyReplays;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter reservationExpiryBatchFailures;

    private final AtomicLong lastExpiryDurationMs = new AtomicLong(0);
    private final AtomicInteger lastExpiryBatchRows = new AtomicInteger(0);
    private final AtomicLong lastOutboxDurationMs = new AtomicLong(0);
    private final AtomicInteger lastOutboxBatchRows = new AtomicInteger(0);

    public ReservationMetrics(MeterRegistry registry) {
        this.reservationsCreated = Counter.builder("reservations_created")
            .description("Total reservations created")
            .register(registry);
        this.reservationsCancelled = Counter.builder("reservations_cancelled")
            .description("Total reservations cancelled")
            .register(registry);
        this.reservationsExpired = Counter.builder("reservations_expired")
            .description("Total reservations expired")
            .register(registry);
        this.capacityRejections = Counter.builder("capacity_rejections")
            .description("Total reservation requests rejected due to insufficient capacity")
            .register(registry);
        this.idempotencyReplays = Counter.builder("idempotency_replays")
            .description("Total idempotent requests replayed from stored response")
            .register(registry);
        this.cacheHits = Counter.builder("cache_hits")
            .description("Total availability cache hits")
            .register(registry);
        this.cacheMisses = Counter.builder("cache_misses")
            .description("Total availability cache misses")
            .register(registry);
        this.reservationExpiryBatchFailures = Counter.builder("reservation_expiry_batch_failures")
            .description("Total failed reservation expiry batches")
            .register(registry);

        Gauge.builder("scheduler_job_duration_seconds", lastExpiryDurationMs, val -> val.get() / 1000.0)
            .tag("job", "reservation_expiry")
            .description("Last execution duration of reservation expiry batch in seconds")
            .register(registry);

        Gauge.builder("scheduler_job_batch_rows", lastExpiryBatchRows, AtomicInteger::get)
            .tag("job", "reservation_expiry")
            .description("Number of rows processed in the last reservation expiry batch")
            .register(registry);

        Gauge.builder("scheduler_job_duration_seconds", lastOutboxDurationMs, val -> val.get() / 1000.0)
            .tag("job", "outbox_publisher")
            .description("Last execution duration of outbox publisher batch in seconds")
            .register(registry);

        Gauge.builder("scheduler_job_batch_rows", lastOutboxBatchRows, AtomicInteger::get)
            .tag("job", "outbox_publisher")
            .description("Number of events processed in the last outbox publisher batch")
            .register(registry);
    }

    public void incrementCreated() {
        reservationsCreated.increment();
    }

    public void incrementCancelled() {
        reservationsCancelled.increment();
    }

    public void incrementExpired() {
        reservationsExpired.increment();
    }

    public void incrementCapacityRejections() {
        capacityRejections.increment();
    }

    public void incrementIdempotencyReplays() {
        idempotencyReplays.increment();
    }

    public void incrementCacheHits() {
        cacheHits.increment();
    }

    public void incrementCacheMisses() {
        cacheMisses.increment();
    }

    public void recordExpiryBatch(int count, long durationMs) {
        lastExpiryBatchRows.set(count);
        lastExpiryDurationMs.set(durationMs);
    }

    public void recordOutboxBatch(int count, long durationMs) {
        lastOutboxBatchRows.set(count);
        lastOutboxDurationMs.set(durationMs);
    }

    public void incrementExpiryBatchFailures() {
        reservationExpiryBatchFailures.increment();
    }
}

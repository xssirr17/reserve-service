package io.github.xssirr17.reserve.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics {

    private final Counter reservationsCreated;
    private final Counter reservationsCancelled;
    private final Counter reservationsExpired;
    private final Counter capacityRejections;
    private final Counter idempotencyReplays;
    private final Counter cacheHits;
    private final Counter cacheMisses;

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
}

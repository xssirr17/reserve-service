package io.github.xssirr17.reserve.idempotency;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyKeyRepository repository;
    private final ReservationProperties properties;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyKeyRepository repository,
                                 ReservationProperties properties,
                                 Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${reserve.idempotency.cleanup-cron:0 0 * * * *}")
    @Transactional
    public int cleanupExpiredKeys() {
        Instant cutoff = clock.instant().minus(properties.getIdempotency().getRetention());
        int deleted = repository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} expired idempotency keys older than {}", deleted, cutoff);
        }
        return deleted;
    }
}

package io.github.xssirr17.reserve.outbox.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ReservationProperties properties;
    private final Clock clock;
    private final ReservationMetrics metrics;
    private final TransactionTemplate requiresNewTxTemplate;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           EventPublisher eventPublisher,
                           ReservationProperties properties,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this(outboxEventRepository, eventPublisher, properties, clock, transactionManager, null);
    }

    @Autowired
    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           EventPublisher eventPublisher,
                           ReservationProperties properties,
                           Clock clock,
                           PlatformTransactionManager transactionManager,
                           ReservationMetrics metrics) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.requiresNewTxTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${reserve.outbox.interval-ms:2000}")
    public void publishBatch() {
        int batchSize = properties.getOutbox().getBatchSize();
        processBatch(batchSize);
    }

    /**
     * Non-transactional batch processor orchestrating:
     * 1. Short Tx 1: claim/lease batch with FOR UPDATE SKIP LOCKED
     * 2. Non-transactional: publish each event via external I/O
     * 3. Short Tx 2 (per event): mark published on success, or reschedule/fail on error
     */
    public int processBatch(int batchSize) {
        long startNanos = System.nanoTime();
        Duration lease = properties.getOutbox().getLeaseDuration();
        List<OutboxEvent> claimed = claimBatch(batchSize, lease);
        if (claimed == null || claimed.isEmpty()) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (metrics != null) {
                metrics.recordOutboxBatch(0, durationMs);
            }
            return 0;
        }

        int maxAttempts = properties.getOutbox().getMaxAttempts();
        Duration retryBackoff = properties.getOutbox().getRetryBackoff();

        for (OutboxEvent event : claimed) {
            try {
                // Outside any DB transaction
                eventPublisher.publish(event);
                markPublished(event);
            } catch (Exception ex) {
                markFailedOrRetry(event, maxAttempts, retryBackoff, ex);
            }
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (metrics != null) {
            metrics.recordOutboxBatch(claimed.size(), durationMs);
        }
        log.info("Outbox publisher batch completed: processed {} events in {} ms", claimed.size(), durationMs);
        return claimed.size();
    }

    public List<OutboxEvent> claimBatch(int batchSize, Duration lease) {
        return requiresNewTxTemplate.execute(status -> {
            Instant now = clock.instant();
            List<OutboxEvent> events = outboxEventRepository.findDueEventsForUpdateSkipLocked(now, batchSize);
            if (events.isEmpty()) {
                return Collections.emptyList();
            }
            Instant lockedUntil = now.plus(lease);
            for (OutboxEvent event : events) {
                event.setLockedUntil(lockedUntil);
            }
            return outboxEventRepository.saveAll(events);
        });
    }

    private void markPublished(OutboxEvent event) {
        requiresNewTxTemplate.executeWithoutResult(status -> {
            outboxEventRepository.findById(event.getId()).ifPresent(e -> {
                e.setStatus("PUBLISHED");
                e.setPublishedAt(clock.instant());
                e.setLockedUntil(null);
                outboxEventRepository.save(e);
            });
        });
    }

    private void markFailedOrRetry(OutboxEvent event, int maxAttempts, Duration retryBackoff, Exception ex) {
        requiresNewTxTemplate.executeWithoutResult(status -> {
            outboxEventRepository.findById(event.getId()).ifPresent(e -> {
                int newAttempts = e.getAttempts() + 1;
                e.setAttempts(newAttempts);
                if (newAttempts >= maxAttempts) {
                    e.setStatus("FAILED");
                    e.setLockedUntil(null);
                    log.error("Outbox event {} marked as FAILED after {} attempts: {}",
                        e.getId(), newAttempts, ex.getMessage());
                } else {
                    e.setLockedUntil(clock.instant().plus(retryBackoff));
                    log.warn("Failed to publish outbox event {} on attempt {}/{}, rescheduled for retry: {}",
                        e.getId(), newAttempts, maxAttempts, ex.getMessage());
                }
                outboxEventRepository.save(e);
            });
        });
    }
}

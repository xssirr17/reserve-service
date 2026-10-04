package io.github.xssirr17.reserve.outbox.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ReservationProperties properties;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           EventPublisher eventPublisher,
                           ReservationProperties properties,
                           Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${reserve.outbox.interval-ms:2000}")
    public void publishBatch() {
        int batchSize = properties.getOutbox().getBatchSize();
        processBatch(batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processBatch(int batchSize) {
        List<OutboxEvent> events = outboxEventRepository.findUnpublishedForUpdateSkipLocked(batchSize);
        if (events.isEmpty()) {
            return 0;
        }

        int maxAttempts = properties.getOutbox().getMaxAttempts();

        for (OutboxEvent event : events) {
            try {
                eventPublisher.publish(event);
                event.setStatus("PUBLISHED");
                event.setPublishedAt(clock.instant());
            } catch (Exception ex) {
                event.setAttempts(event.getAttempts() + 1);
                if (event.getAttempts() >= maxAttempts) {
                    event.setStatus("FAILED");
                    log.error("Outbox event {} marked as FAILED after {} attempts: {}",
                        event.getId(), event.getAttempts(), ex.getMessage());
                } else {
                    log.warn("Failed to publish outbox event {} on attempt {}/{}: {}",
                        event.getId(), event.getAttempts(), maxAttempts, ex.getMessage());
                }
            }
        }
        outboxEventRepository.saveAll(events);
        return events.size();
    }
}

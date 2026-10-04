package io.github.xssirr17.reserve.reservation.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.slot.domain.SlotRepository;
import io.github.xssirr17.reserve.slot.service.AvailabilityCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationExpiryBatchProcessor batchProcessor;
    private final ReservationProperties properties;
    private final ReservationMetrics metrics;

    @Autowired
    public ReservationExpiryJob(ReservationExpiryBatchProcessor batchProcessor,
                                ReservationProperties properties,
                                ReservationMetrics metrics) {
        this.batchProcessor = batchProcessor;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Backwards-compatible constructor for manual test instantiation.
     */
    public ReservationExpiryJob(ReservationRepository reservationRepository,
                                SlotRepository slotRepository,
                                OutboxEventRepository outboxEventRepository,
                                AvailabilityCacheService availabilityCacheService,
                                ReservationProperties properties,
                                ReservationMetrics metrics,
                                Clock clock) {
        this(new ReservationExpiryBatchProcessor(reservationRepository, slotRepository, outboxEventRepository,
                availabilityCacheService, metrics, clock), properties, metrics);
    }

    @Scheduled(fixedDelayString = "${reserve.expiry.interval-ms:5000}")
    public void runExpiry() {
        int batchSize = properties.getExpiry().getBatchSize();
        while (true) {
            try {
                int processed = batchProcessor.processBatch(batchSize);
                if (processed < batchSize) {
                    break;
                }
            } catch (Exception ex) {
                if (metrics != null) {
                    metrics.incrementExpiryBatchFailures();
                }
                log.error("Failed to process reservation expiry batch: {}", ex.getMessage(), ex);
                break;
            }
        }
    }

    public int processBatch(int batchSize) {
        return batchProcessor.processBatch(batchSize);
    }
}

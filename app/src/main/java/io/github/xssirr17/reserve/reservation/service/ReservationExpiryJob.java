package io.github.xssirr17.reserve.reservation.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import io.github.xssirr17.reserve.reservation.domain.Reservation;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
import io.github.xssirr17.reserve.slot.domain.SlotRepository;
import io.github.xssirr17.reserve.slot.service.AvailabilityCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationRepository reservationRepository;
    private final SlotRepository slotRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AvailabilityCacheService availabilityCacheService;
    private final ReservationProperties properties;
    private final ReservationMetrics metrics;
    private final Clock clock;

    public ReservationExpiryJob(ReservationRepository reservationRepository,
                                SlotRepository slotRepository,
                                OutboxEventRepository outboxEventRepository,
                                AvailabilityCacheService availabilityCacheService,
                                ReservationProperties properties,
                                ReservationMetrics metrics,
                                Clock clock) {
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.availabilityCacheService = availabilityCacheService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${reserve.expiry.interval-ms:5000}")
    public void runExpiry() {
        int batchSize = properties.getExpiry().getBatchSize();
        while (true) {
            try {
                int processed = processBatch(batchSize);
                if (processed < batchSize) {
                    break;
                }
            } catch (Exception ex) {
                log.error("Failed to process reservation expiry batch: {}", ex.getMessage(), ex);
                break;
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processBatch(int batchSize) {
        Instant now = clock.instant();
        List<Reservation> expiredList = reservationRepository.findExpiredPendingForUpdateSkipLocked(now, batchSize);

        if (expiredList.isEmpty()) {
            return 0;
        }

        for (Reservation reservation : expiredList) {
            reservation.setStatus(ReservationStatus.EXPIRED);
            slotRepository.release(reservation.getSlotId(), reservation.getQuantity());

            String payload = String.format("{\"reservationId\":\"%s\",\"slotId\":\"%s\",\"userId\":\"%s\",\"quantity\":%d,\"status\":\"EXPIRED\"}",
                reservation.getId(), reservation.getSlotId(), reservation.getUserId(), reservation.getQuantity());
            outboxEventRepository.save(new OutboxEvent("RESERVATION", reservation.getId().toString(), "ReservationExpired", payload));

            metrics.incrementExpired();

            slotRepository.findById(reservation.getSlotId())
                .ifPresent(slot -> availabilityCacheService.invalidateResourceAfterCommit(slot.getResourceId()));
        }

        reservationRepository.saveAll(expiredList);
        log.info("Expired and released capacity for {} reservations", expiredList.size());
        return expiredList.size();
    }
}

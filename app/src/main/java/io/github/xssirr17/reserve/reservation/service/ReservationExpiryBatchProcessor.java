package io.github.xssirr17.reserve.reservation.service;

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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dedicated Spring bean handling transactional processing of reservation expiry batches.
 * Separated from ReservationExpiryJob to ensure @Transactional(REQUIRES_NEW) is applied
 * via Spring AOP proxy rather than bypassed by internal self-invocation.
 */
@Component
public class ReservationExpiryBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryBatchProcessor.class);

    private final ReservationRepository reservationRepository;
    private final SlotRepository slotRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AvailabilityCacheService availabilityCacheService;
    private final ReservationMetrics metrics;
    private final Clock clock;

    public ReservationExpiryBatchProcessor(ReservationRepository reservationRepository,
                                          SlotRepository slotRepository,
                                          OutboxEventRepository outboxEventRepository,
                                          AvailabilityCacheService availabilityCacheService,
                                          ReservationMetrics metrics,
                                          Clock clock) {
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.availabilityCacheService = availabilityCacheService;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processBatch(int batchSize) {
        long startNanos = System.nanoTime();
        try {
            Instant now = clock.instant();
            List<Reservation> expiredList = new ArrayList<>(reservationRepository.findExpiredPendingForUpdateSkipLocked(now, batchSize));

            if (expiredList.isEmpty()) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (metrics != null) {
                    metrics.recordExpiryBatch(0, durationMs);
                }
                return 0;
            }

            // Sort reservations consistently by slotId, then id
            expiredList.sort(Comparator.comparing(Reservation::getSlotId).thenComparing(Reservation::getId));

            // Aggregate capacity release per slot to minimize DB round-trips and prevent deadlocks
            Map<UUID, Integer> releaseBySlot = new LinkedHashMap<>();
            for (Reservation reservation : expiredList) {
                reservation.setStatus(ReservationStatus.EXPIRED);
                releaseBySlot.merge(reservation.getSlotId(), reservation.getQuantity(), Integer::sum);

                String payload = String.format("{\"reservationId\":\"%s\",\"slotId\":\"%s\",\"userId\":\"%s\",\"quantity\":%d,\"status\":\"EXPIRED\"}",
                    reservation.getId(), reservation.getSlotId(), reservation.getUserId(), reservation.getQuantity());
                outboxEventRepository.save(new OutboxEvent("RESERVATION", reservation.getId().toString(), "ReservationExpired", payload));

                if (metrics != null) {
                    metrics.incrementExpired();
                }
            }

            // Acquire slot row locks in strictly sorted order of slotId
            List<UUID> sortedSlotIds = new ArrayList<>(releaseBySlot.keySet());
            Collections.sort(sortedSlotIds);

            Set<UUID> resourceIdsToInvalidate = new HashSet<>();
            for (UUID slotId : sortedSlotIds) {
                int totalQuantity = releaseBySlot.get(slotId);
                slotRepository.release(slotId, totalQuantity);
                slotRepository.findById(slotId)
                    .ifPresent(slot -> resourceIdsToInvalidate.add(slot.getResourceId()));
            }

            for (UUID resourceId : resourceIdsToInvalidate) {
                availabilityCacheService.invalidateResourceAfterCommit(resourceId);
            }

            reservationRepository.saveAll(expiredList);
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (metrics != null) {
                metrics.recordExpiryBatch(expiredList.size(), durationMs);
            }
            log.info("Expired and released capacity for {} reservations across {} slots in {} ms",
                expiredList.size(), sortedSlotIds.size(), durationMs);
            return expiredList.size();
        } catch (Exception ex) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (metrics != null) {
                metrics.recordExpiryBatch(0, durationMs);
            }
            throw ex;
        }
    }
}

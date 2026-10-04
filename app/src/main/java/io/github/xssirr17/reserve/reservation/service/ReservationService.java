package io.github.xssirr17.reserve.reservation.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.error.ConflictException;
import io.github.xssirr17.reserve.common.error.InsufficientCapacityException;
import io.github.xssirr17.reserve.common.error.InvalidStateTransitionException;
import io.github.xssirr17.reserve.common.error.NotFoundException;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import io.github.xssirr17.reserve.idempotency.IdempotencyService;
import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import io.github.xssirr17.reserve.reservation.domain.Reservation;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
import io.github.xssirr17.reserve.reservation.dto.CreateReservationRequest;
import io.github.xssirr17.reserve.reservation.dto.ReservationResponse;
import io.github.xssirr17.reserve.slot.domain.Slot;
import io.github.xssirr17.reserve.slot.domain.SlotRepository;
import io.github.xssirr17.reserve.slot.service.AvailabilityCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final SlotRepository slotRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyService idempotencyService;
    private final AvailabilityCacheService availabilityCacheService;
    private final ReservationProperties properties;
    private final ReservationMetrics metrics;
    private final Clock clock;

    public ReservationService(ReservationRepository reservationRepository,
                              SlotRepository slotRepository,
                              OutboxEventRepository outboxEventRepository,
                              IdempotencyService idempotencyService,
                              AvailabilityCacheService availabilityCacheService,
                              ReservationProperties properties,
                              ReservationMetrics metrics,
                              Clock clock) {
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.idempotencyService = idempotencyService;
        this.availabilityCacheService = availabilityCacheService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Create a reservation. If an idempotency key is provided, wraps execution with IdempotencyService.
     */
    public ReservationResponse createReservation(CreateReservationRequest request, String idempotencyKey) {
        String scope = "user:" + request.userId() + ":POST:/api/reservations";
        return idempotencyService.execute(scope, idempotencyKey, request, ReservationResponse.class, () ->
            createReservationInternal(request)
        );
    }

    /**
     * Internal atomic creation method:
     * 1. Validates slot existence and start_time not in the past.
     * 2. Atomic conditional UPDATE on slot reserved capacity.
     * 3. Inserts Reservation with status PENDING and hold expiration.
     * 4. Writes ReservationCreated outbox event in the SAME transaction.
     * 5. Registers after-commit cache invalidation for the resource.
     */
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse createReservationInternal(CreateReservationRequest request) {
        UUID slotId = request.slotId();
        int quantity = request.quantity();

        Slot slot = slotRepository.findById(slotId)
            .orElseThrow(() -> new NotFoundException("Slot not found: " + slotId));

        if (slot.getStartTime().isBefore(clock.instant())) {
            throw new ConflictException("Cannot reserve a slot that has already started or is in the past");
        }

        // 2. Atomic capacity reservation - never read-then-write in Java
        int rowsAffected = slotRepository.reserve(slotId, quantity);
        if (rowsAffected == 0) {
            metrics.incrementCapacityRejections();
            int available = Math.max(0, slot.getCapacity() - slot.getReserved());
            throw new InsufficientCapacityException(slotId, quantity, available);
        }

        // 3. Persist reservation in PENDING state
        Instant expiresAt = clock.instant().plus(properties.getHoldDuration());
        Reservation reservation = new Reservation(slotId, request.userId(), quantity, ReservationStatus.PENDING, expiresAt);
        Reservation saved = reservationRepository.save(reservation);

        // 4. Outbox event in SAME transaction
        String payload = String.format("{\"reservationId\":\"%s\",\"slotId\":\"%s\",\"userId\":\"%s\",\"quantity\":%d,\"expiresAt\":\"%s\"}",
            saved.getId(), saved.getSlotId(), saved.getUserId(), saved.getQuantity(), expiresAt);
        outboxEventRepository.save(new OutboxEvent("RESERVATION", saved.getId().toString(), "ReservationCreated", payload));

        // 5. Invalidate availability cache after transaction commit
        availabilityCacheService.invalidateResourceAfterCommit(slot.getResourceId());
        metrics.incrementCreated();

        log.info("Created PENDING reservation {} for slot {} (expires at {})", saved.getId(), slotId, expiresAt);
        return ReservationResponse.from(saved);
    }

    /**
     * Lock ordering rule: Reservation row first (PESSIMISTIC_WRITE lock), then slot row if capacity changed.
     */
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse confirmReservation(UUID id) {
        Reservation reservation = reservationRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Reservation not found: " + id));

        // Idempotent return if already confirmed
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Reservation {} is already CONFIRMED (idempotent return)", id);
            return ReservationResponse.from(reservation);
        }

        // State machine validation
        if (!reservation.getStatus().canTransitionTo(ReservationStatus.CONFIRMED)) {
            throw new InvalidStateTransitionException(reservation.getStatus(), ReservationStatus.CONFIRMED);
        }

        // Reject if expired
        if (reservation.getExpiresAt() != null && reservation.getExpiresAt().isBefore(clock.instant())) {
            throw new ConflictException("Reservation " + id + " has expired and cannot be confirmed");
        }

        reservation.setStatus(ReservationStatus.CONFIRMED);
        Reservation saved = reservationRepository.save(reservation);

        // Outbox event in same transaction
        String payload = String.format("{\"reservationId\":\"%s\",\"slotId\":\"%s\",\"userId\":\"%s\",\"status\":\"CONFIRMED\"}",
            saved.getId(), saved.getSlotId(), saved.getUserId());
        outboxEventRepository.save(new OutboxEvent("RESERVATION", saved.getId().toString(), "ReservationConfirmed", payload));

        log.info("Confirmed reservation {}", id);
        return ReservationResponse.from(saved);
    }

    /**
     * Cancels reservation and releases capacity.
     * Lock ordering rule: Reservation row locked first via findByIdForUpdate, then slot capacity released.
     */
    @Transactional(rollbackFor = Exception.class)
    public ReservationResponse cancelReservation(UUID id) {
        Reservation reservation = reservationRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Reservation not found: " + id));

        // Idempotent return if already cancelled
        if (reservation.getStatus() == ReservationStatus.CANCELLED) {
            log.info("Reservation {} is already CANCELLED (idempotent return, no duplicate release)", id);
            return ReservationResponse.from(reservation);
        }

        // Terminal states check
        if (reservation.getStatus().isTerminal()) {
            throw new InvalidStateTransitionException(reservation.getStatus(), ReservationStatus.CANCELLED);
        }

        if (!reservation.getStatus().canTransitionTo(ReservationStatus.CANCELLED)) {
            throw new InvalidStateTransitionException(reservation.getStatus(), ReservationStatus.CANCELLED);
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        Reservation saved = reservationRepository.save(reservation);

        // Release capacity ONLY once because we verified state transition
        slotRepository.release(reservation.getSlotId(), reservation.getQuantity());

        // Outbox event in same transaction
        String payload = String.format("{\"reservationId\":\"%s\",\"slotId\":\"%s\",\"userId\":\"%s\",\"quantity\":%d}",
            saved.getId(), saved.getSlotId(), saved.getUserId(), saved.getQuantity());
        outboxEventRepository.save(new OutboxEvent("RESERVATION", saved.getId().toString(), "ReservationCancelled", payload));

        // Invalidate availability cache after commit
        slotRepository.findById(reservation.getSlotId())
            .ifPresent(slot -> availabilityCacheService.invalidateResourceAfterCommit(slot.getResourceId()));

        metrics.incrementCancelled();
        log.info("Cancelled reservation {} and released {} capacity on slot {}", id, reservation.getQuantity(), reservation.getSlotId());
        return ReservationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID id) {
        return reservationRepository.findById(id)
            .map(ReservationResponse::from)
            .orElseThrow(() -> new NotFoundException("Reservation not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<ReservationResponse> listByUser(String userId, Pageable pageable) {
        return reservationRepository.findByUserId(userId, pageable)
            .map(ReservationResponse::from);
    }
}

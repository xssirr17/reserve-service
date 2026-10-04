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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private AvailabilityCacheService availabilityCacheService;
    @Mock
    private ReservationMetrics metrics;

    private ReservationProperties properties;
    private Clock fixedClock;
    private Instant now;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-04T12:00:00Z");
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);

        properties = new ReservationProperties();
        properties.setHoldDuration(Duration.ofMinutes(15));

        reservationService = new ReservationService(
            reservationRepository,
            slotRepository,
            outboxEventRepository,
            idempotencyService,
            availabilityCacheService,
            properties,
            metrics,
            fixedClock
        );
    }

    @Test
    @DisplayName("createReservation: delegates through IdempotencyService and executes creation")
    void testCreateReservationDelegatesThroughIdempotency() {
        UUID slotId = UUID.randomUUID();
        CreateReservationRequest request = new CreateReservationRequest(slotId, "user1", 2);

        when(idempotencyService.execute(any(), eq("key-123"), eq(request), eq(ReservationResponse.class), any()))
            .thenAnswer(invocation -> {
                Supplier<ReservationResponse> supplier = invocation.getArgument(4);
                return supplier.get();
            });

        Slot slot = new Slot(UUID.randomUUID(), now.plusSeconds(3600), now.plusSeconds(7200), 10);
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));
        when(slotRepository.reserve(slotId, 2)).thenReturn(1);

        Reservation savedReservation = new Reservation(slotId, "user1", 2, ReservationStatus.PENDING, now.plus(Duration.ofMinutes(15)));
        ReflectionTestUtils.setField(savedReservation, "id", UUID.randomUUID());
        when(reservationRepository.save(any(Reservation.class))).thenReturn(savedReservation);

        ReservationResponse response = reservationService.createReservation(request, "key-123");

        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo("user1");
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);

        verify(slotRepository).reserve(slotId, 2);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(availabilityCacheService).invalidateResourceAfterCommit(slot.getResourceId());
        verify(metrics).incrementCreated();
    }

    @Test
    @DisplayName("createReservationInternal: throws NotFoundException when slot does not exist")
    void testCreateSlotNotFound() {
        UUID slotId = UUID.randomUUID();
        CreateReservationRequest request = new CreateReservationRequest(slotId, "user1", 2);

        when(slotRepository.findById(slotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.createReservationInternal(request))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Slot not found");

        verify(slotRepository, never()).reserve(any(), anyInt());
    }

    @Test
    @DisplayName("createReservationInternal: throws ConflictException when slot is in the past")
    void testCreateSlotInPast() {
        UUID slotId = UUID.randomUUID();
        CreateReservationRequest request = new CreateReservationRequest(slotId, "user1", 2);

        Slot pastSlot = new Slot(UUID.randomUUID(), now.minusSeconds(3600), now.minusSeconds(1800), 10);
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(pastSlot));

        assertThatThrownBy(() -> reservationService.createReservationInternal(request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Cannot reserve a slot that has already started or is in the past");

        verify(slotRepository, never()).reserve(any(), anyInt());
    }

    @Test
    @DisplayName("createReservationInternal: throws InsufficientCapacityException when atomic reserve returns 0 rows")
    void testCreateInsufficientCapacity() {
        UUID slotId = UUID.randomUUID();
        CreateReservationRequest request = new CreateReservationRequest(slotId, "user1", 5);

        Slot slot = new Slot(UUID.randomUUID(), now.plusSeconds(3600), now.plusSeconds(7200), 10);
        slot.setReserved(8); // only 2 left, requested 5
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));
        when(slotRepository.reserve(slotId, 5)).thenReturn(0);

        assertThatThrownBy(() -> reservationService.createReservationInternal(request))
            .isInstanceOf(InsufficientCapacityException.class)
            .satisfies(ex -> {
                InsufficientCapacityException ice = (InsufficientCapacityException) ex;
                assertThat(ice.getSlotId()).isEqualTo(slotId);
                assertThat(ice.getRequested()).isEqualTo(5);
                assertThat(ice.getAvailable()).isEqualTo(2);
            });

        verify(metrics).incrementCapacityRejections();
        verify(reservationRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirmReservation: successfully confirms PENDING reservation and records outbox event")
    void testConfirmSuccess() {
        UUID resId = UUID.randomUUID();
        Reservation reservation = new Reservation(UUID.randomUUID(), "user1", 2, ReservationStatus.PENDING, now.plusSeconds(600));
        ReflectionTestUtils.setField(reservation, "id", resId);

        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));

        ReservationResponse response = reservationService.confirmReservation(resId);

        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("confirmReservation: returns current state idempotently if already CONFIRMED")
    void testConfirmIdempotent() {
        UUID resId = UUID.randomUUID();
        Reservation reservation = new Reservation(UUID.randomUUID(), "user1", 2, ReservationStatus.CONFIRMED, now.plusSeconds(600));
        ReflectionTestUtils.setField(reservation, "id", resId);

        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(reservation));

        ReservationResponse response = reservationService.confirmReservation(resId);

        assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(reservationRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirmReservation: throws ConflictException when reservation is expired")
    void testConfirmExpired() {
        UUID resId = UUID.randomUUID();
        Reservation expiredReservation = new Reservation(UUID.randomUUID(), "user1", 2, ReservationStatus.PENDING, now.minusSeconds(10));
        ReflectionTestUtils.setField(expiredReservation, "id", resId);

        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(expiredReservation));

        assertThatThrownBy(() -> reservationService.confirmReservation(resId))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("confirmReservation: throws InvalidStateTransitionException if status cannot transition")
    void testConfirmInvalidTransition() {
        UUID resId = UUID.randomUUID();
        Reservation cancelledReservation = new Reservation(UUID.randomUUID(), "user1", 2, ReservationStatus.CANCELLED, null);
        ReflectionTestUtils.setField(cancelledReservation, "id", resId);

        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(cancelledReservation));

        assertThatThrownBy(() -> reservationService.confirmReservation(resId))
            .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("cancelReservation: transitions to CANCELLED and releases capacity exactly once")
    void testCancelSuccess() {
        UUID resId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        Reservation reservation = new Reservation(slotId, "user1", 3, ReservationStatus.PENDING, now.plusSeconds(600));
        ReflectionTestUtils.setField(reservation, "id", resId);

        Slot slot = new Slot(UUID.randomUUID(), now.plusSeconds(3600), now.plusSeconds(7200), 10);
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(slot));
        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));

        ReservationResponse response = reservationService.cancelReservation(resId);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(slotRepository).release(slotId, 3);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(availabilityCacheService).invalidateResourceAfterCommit(slot.getResourceId());
        verify(metrics).incrementCancelled();
    }

    @Test
    @DisplayName("cancelReservation: returns current state idempotently without releasing capacity again")
    void testCancelIdempotent() {
        UUID resId = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();
        Reservation reservation = new Reservation(slotId, "user1", 3, ReservationStatus.CANCELLED, null);
        ReflectionTestUtils.setField(reservation, "id", resId);

        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(reservation));

        ReservationResponse response = reservationService.cancelReservation(resId);

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(slotRepository, never()).release(any(), anyInt());
        verify(outboxEventRepository, never()).save(any());
        verify(metrics, never()).incrementCancelled();
    }

    @Test
    @DisplayName("cancelReservation: throws InvalidStateTransitionException on terminal states (COMPLETED, EXPIRED)")
    void testCancelTerminalStates() {
        UUID resId = UUID.randomUUID();
        Reservation completed = new Reservation(UUID.randomUUID(), "user1", 2, ReservationStatus.COMPLETED, null);
        ReflectionTestUtils.setField(completed, "id", resId);

        when(reservationRepository.findByIdForUpdate(resId)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> reservationService.cancelReservation(resId))
            .isInstanceOf(InvalidStateTransitionException.class);
    }
}

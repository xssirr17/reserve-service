package io.github.xssirr17.reserve.reservation.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import io.github.xssirr17.reserve.reservation.domain.Reservation;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
import io.github.xssirr17.reserve.slot.domain.Slot;
import io.github.xssirr17.reserve.slot.domain.SlotRepository;
import io.github.xssirr17.reserve.slot.service.AvailabilityCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationExpiryJobTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private SlotRepository slotRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private AvailabilityCacheService availabilityCacheService;
    @Mock
    private ReservationMetrics metrics;

    private ReservationProperties properties;
    private Clock fixedClock;
    private Instant now;

    private ReservationExpiryJob expiryJob;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-04T12:00:00Z");
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);

        properties = new ReservationProperties();
        properties.getExpiry().setBatchSize(10);

        expiryJob = new ReservationExpiryJob(
            reservationRepository,
            slotRepository,
            outboxEventRepository,
            availabilityCacheService,
            properties,
            metrics,
            fixedClock
        );
    }

    @Test
    @DisplayName("processBatch: transitions expired reservations, releases capacity, and writes outbox events")
    void testProcessBatchExpiresAndReleases() {
        UUID resId1 = UUID.randomUUID();
        UUID slotId1 = UUID.randomUUID();
        Reservation r1 = new Reservation(slotId1, "user1", 2, ReservationStatus.PENDING, now.minusSeconds(60));
        ReflectionTestUtils.setField(r1, "id", resId1);

        UUID resId2 = UUID.randomUUID();
        UUID slotId2 = UUID.randomUUID();
        Reservation r2 = new Reservation(slotId2, "user2", 3, ReservationStatus.PENDING, now.minusSeconds(30));
        ReflectionTestUtils.setField(r2, "id", resId2);

        when(reservationRepository.findExpiredPendingForUpdateSkipLocked(now, 10))
            .thenReturn(List.of(r1, r2));

        Slot slot1 = new Slot(UUID.randomUUID(), now.plusSeconds(3600), now.plusSeconds(7200), 10);
        when(slotRepository.findById(slotId1)).thenReturn(Optional.of(slot1));

        Slot slot2 = new Slot(UUID.randomUUID(), now.plusSeconds(3600), now.plusSeconds(7200), 10);
        when(slotRepository.findById(slotId2)).thenReturn(Optional.of(slot2));

        int processed = expiryJob.processBatch(10);

        assertThat(processed).isEqualTo(2);
        assertThat(r1.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(r2.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        verify(slotRepository).release(slotId1, 2);
        verify(slotRepository).release(slotId2, 3);
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
        verify(metrics, times(2)).incrementExpired();
        verify(availabilityCacheService).invalidateResourceAfterCommit(slot1.getResourceId());
        verify(availabilityCacheService).invalidateResourceAfterCommit(slot2.getResourceId());
        verify(reservationRepository).saveAll(List.of(r1, r2));
    }

    @Test
    @DisplayName("processBatch: returns 0 when no reservations have expired")
    void testProcessBatchNoExpired() {
        when(reservationRepository.findExpiredPendingForUpdateSkipLocked(now, 10))
            .thenReturn(List.of());

        int processed = expiryJob.processBatch(10);

        assertThat(processed).isEqualTo(0);
        verify(slotRepository, never()).release(any(), anyInt());
        verify(outboxEventRepository, never()).save(any());
        verify(metrics, never()).incrementExpired();
    }
}

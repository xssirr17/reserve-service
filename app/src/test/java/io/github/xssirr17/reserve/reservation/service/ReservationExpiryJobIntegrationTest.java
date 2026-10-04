package io.github.xssirr17.reserve.reservation.service;

import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import io.github.xssirr17.reserve.reservation.domain.Reservation;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
import io.github.xssirr17.reserve.resource.domain.Resource;
import io.github.xssirr17.reserve.resource.domain.ResourceRepository;
import io.github.xssirr17.reserve.slot.domain.Slot;
import io.github.xssirr17.reserve.slot.domain.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/reserve}",
    "spring.datasource.username=${TEST_DB_USERNAME:postgres}",
    "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.data.redis.host=${TEST_REDIS_HOST:localhost}",
    "spring.data.redis.port=${TEST_REDIS_PORT:6379}",
    "spring.datasource.hikari.maximum-pool-size=20",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class ReservationExpiryJobIntegrationTest {

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ReservationExpiryJob reservationExpiryJob;

    @Autowired
    private Clock clock;

    private Resource testResource;
    private Slot testSlot;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        reservationRepository.deleteAll();
        slotRepository.deleteAll();
        resourceRepository.deleteAll();

        testResource = resourceRepository.save(new Resource("Expiry Test Resource " + UUID.randomUUID(), "ROOM", Map.of()));
        Instant startTime = clock.instant().plus(2, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(1, ChronoUnit.HOURS);
        Slot slot = new Slot(testResource.getId(), startTime, endTime, 10);
        testSlot = slotRepository.save(slot);
    }

    @Test
    @DisplayName("runExpiry scheduled entry point transitions expired reservations, releases capacity, and writes exactly one outbox event")
    void testRunExpiryTransitionsExpiredReservationsAndReleasesCapacity() {
        // Given a PENDING reservation with expires_at in the past
        Instant pastExpiry = clock.instant().minus(5, ChronoUnit.MINUTES);
        int quantity = 3;

        // Set slot.reserved = 3 (simulating capacity held by pending reservation)
        ReflectionTestUtils.setField(testSlot, "reserved", quantity);
        testSlot = slotRepository.save(testSlot);

        Reservation reservation = new Reservation(testSlot.getId(), "user-" + UUID.randomUUID(), quantity, ReservationStatus.PENDING, pastExpiry);
        reservation = reservationRepository.save(reservation);
        UUID reservationId = reservation.getId();

        // When invoking the REAL scheduled entry point runExpiry()
        reservationExpiryJob.runExpiry();

        // Then verify: status = EXPIRED
        Reservation updatedReservation = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(updatedReservation.getStatus())
            .as("Reservation status must be transitioned to EXPIRED")
            .isEqualTo(ReservationStatus.EXPIRED);

        // slot.reserved decreased by the quantity (down from 3 to 0)
        Slot updatedSlot = slotRepository.findById(testSlot.getId()).orElseThrow();
        assertThat(updatedSlot.getReserved())
            .as("Slot reserved capacity must be decreased by the expired reservation quantity")
            .isEqualTo(0);

        // exactly ONE ReservationExpired outbox row for that reservation
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll().stream()
            .filter(e -> e.getAggregateId().equals(reservationId.toString()) && "ReservationExpired".equals(e.getEventType()))
            .toList();
        assertThat(outboxEvents)
            .as("Exactly ONE ReservationExpired outbox event must be written for the expired reservation")
            .hasSize(1);

        // When a second call to runExpiry() is made
        reservationExpiryJob.runExpiry();

        // Then verify a second call changes nothing (no new outbox row, no capacity change)
        Slot slotAfterSecondRun = slotRepository.findById(testSlot.getId()).orElseThrow();
        assertThat(slotAfterSecondRun.getReserved())
            .as("Second runExpiry() call must not modify slot capacity")
            .isEqualTo(0);

        List<OutboxEvent> outboxEventsAfterSecondRun = outboxEventRepository.findAll().stream()
            .filter(e -> e.getAggregateId().equals(reservationId.toString()) && "ReservationExpired".equals(e.getEventType()))
            .toList();
        assertThat(outboxEventsAfterSecondRun)
            .as("Second runExpiry() call must not create duplicate outbox events")
            .hasSize(1);
    }
}

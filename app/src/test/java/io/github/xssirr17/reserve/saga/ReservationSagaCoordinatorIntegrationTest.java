package io.github.xssirr17.reserve.saga;

import io.github.xssirr17.reserve.common.error.InvalidStateTransitionException;
import io.github.xssirr17.reserve.idempotency.IdempotencyKeyRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
import io.github.xssirr17.reserve.reservation.dto.CreateReservationRequest;
import io.github.xssirr17.reserve.reservation.dto.ReservationResponse;
import io.github.xssirr17.reserve.reservation.service.ReservationService;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class ReservationSagaCoordinatorIntegrationTest {

    @Autowired
    private ReservationSagaCoordinator sagaCoordinator;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FakePaymentGateway paymentGateway;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private Slot testSlot;

    @BeforeEach
    void setUp() {
        paymentGateway.reset();
        reservationRepository.deleteAll();
        slotRepository.deleteAll();
        resourceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();

        Resource resource = resourceRepository.save(new Resource("Saga Resource", "ROOM", Map.of()));
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
        Instant end = start.plus(1, ChronoUnit.HOURS);
        testSlot = slotRepository.save(new Slot(resource.getId(), start, end, 10));
    }

    @Test
    @DisplayName("Two concurrent confirm requests for one reservation record exactly ONE charge")
    void testConcurrentConfirmRecordsSingleCharge() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(testSlot.getId(), "saga-user", 2);
        ReservationResponse reservation = reservationService.createReservation(request, "saga-key-" + UUID.randomUUID()).body();
        UUID reservationId = reservation.id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<ReservationResponse> f1 = executor.submit(() -> {
            startLatch.await();
            return sagaCoordinator.executeConfirmationSaga(reservationId);
        });

        Future<ReservationResponse> f2 = executor.submit(() -> {
            startLatch.await();
            return sagaCoordinator.executeConfirmationSaga(reservationId);
        });

        startLatch.countDown();
        ReservationResponse r1 = f1.get(10, TimeUnit.SECONDS);
        ReservationResponse r2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(r1.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(r2.status()).isEqualTo(ReservationStatus.CONFIRMED);

        // Exactly ONE charge recorded by payment gateway!
        assertThat(paymentGateway.getChargeCount()).isEqualTo(1);

        // Subsequent confirm on already CONFIRMED reservation -> zero extra charges
        ReservationResponse r3 = sagaCoordinator.executeConfirmationSaga(reservationId);
        assertThat(r3.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(paymentGateway.getChargeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Confirm on CANCELLED reservation throws conflict and does not charge gateway")
    void testConfirmOnCancelledThrowsConflict() {
        CreateReservationRequest request = new CreateReservationRequest(testSlot.getId(), "cancel-user", 1);
        ReservationResponse reservation = reservationService.createReservation(request, "cancel-key-" + UUID.randomUUID()).body();
        reservationService.cancelReservation(reservation.id());

        assertThatThrownBy(() -> sagaCoordinator.executeConfirmationSaga(reservation.id()))
            .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(paymentGateway.getChargeCount()).isEqualTo(0);
    }
}

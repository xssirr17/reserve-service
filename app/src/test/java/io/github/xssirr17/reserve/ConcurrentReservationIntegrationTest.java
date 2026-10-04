package io.github.xssirr17.reserve;

import io.github.xssirr17.reserve.common.error.InsufficientCapacityException;
import io.github.xssirr17.reserve.idempotency.IdempotencyKeyRepository;
import io.github.xssirr17.reserve.reservation.domain.Reservation;
import io.github.xssirr17.reserve.reservation.domain.ReservationRepository;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
import io.github.xssirr17.reserve.reservation.dto.CreateReservationRequest;
import io.github.xssirr17.reserve.reservation.dto.ReservationResponse;
import io.github.xssirr17.reserve.reservation.service.ReservationExpiryJob;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://localhost:5432/reserve}",
    "spring.datasource.username=${TEST_DB_USERNAME:postgres}",
    "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.data.redis.host=${TEST_REDIS_HOST:localhost}",
    "spring.data.redis.port=${TEST_REDIS_PORT:6379}",
    "spring.datasource.hikari.maximum-pool-size=30",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class ConcurrentReservationIntegrationTest {

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpiryJob reservationExpiryJob;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Resource testResource;

    @BeforeEach
    void setUp() {
        testResource = resourceRepository.save(
            new Resource("Test Room " + UUID.randomUUID(), "ROOM", Map.of())
        );
    }

    @Test
    @DisplayName("100 parallel create requests on a slot with capacity 1: exactly one succeeds, reserved ends at 1")
    void test100ParallelCreatesCapacityOne() throws Exception {
        Instant startTime = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(1, ChronoUnit.HOURS);
        Slot slot = slotRepository.save(new Slot(testResource.getId(), startTime, endTime, 1));
        UUID slotId = slot.getId();

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger capacityRejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CreateReservationRequest request = new CreateReservationRequest(slotId, "user-" + index, 1);
                    reservationService.createReservation(request, "key-" + UUID.randomUUID());
                    successCount.incrementAndGet();
                } catch (InsufficientCapacityException ex) {
                    capacityRejectedCount.incrementAndGet();
                } catch (Exception ex) {
                    // unexpected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(capacityRejectedCount.get()).isEqualTo(99);

        Slot updatedSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(updatedSlot.getReserved()).isEqualTo(1);
    }

    @Test
    @DisplayName("Parallel cancel of the same reservation: capacity released exactly once")
    void testParallelCancelSameReservation() throws Exception {
        Instant startTime = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(1, ChronoUnit.HOURS);
        Slot slot = slotRepository.save(new Slot(testResource.getId(), startTime, endTime, 5));
        UUID slotId = slot.getId();

        CreateReservationRequest request = new CreateReservationRequest(slotId, "cancel-user", 3);
        ReservationResponse reservation = reservationService.createReservation(request, "cancel-key-" + UUID.randomUUID()).body();
        UUID reservationId = reservation.id();

        Slot afterReserve = slotRepository.findById(slotId).orElseThrow();
        assertThat(afterReserve.getReserved()).isEqualTo(3);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    reservationService.cancelReservation(reservationId);
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    // unexpected
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount); // All calls return cleanly

        Slot afterCancel = slotRepository.findById(slotId).orElseThrow();
        assertThat(afterCancel.getReserved()).isEqualTo(0); // Released 3 capacity exactly once!
    }

    @Test
    @DisplayName("Same Idempotency-Key sent concurrently: one reservation created, same response for all")
    void testConcurrentSameIdempotencyKey() throws Exception {
        Instant startTime = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(1, ChronoUnit.HOURS);
        Slot slot = slotRepository.save(new Slot(testResource.getId(), startTime, endTime, 10));
        UUID slotId = slot.getId();

        String sharedKey = "shared-idem-" + UUID.randomUUID();
        CreateReservationRequest request = new CreateReservationRequest(slotId, "idem-user", 2);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<ReservationResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return reservationService.createReservation(request, sharedKey).body();
            }));
        }

        startLatch.countDown();
        Set<UUID> reservationIds = Collections.newSetFromMap(new ConcurrentHashMap<>());

        for (Future<ReservationResponse> future : futures) {
            try {
                ReservationResponse res = future.get(10, TimeUnit.SECONDS);
                reservationIds.add(res.id());
            } catch (Exception ignored) {
                // Some threads may receive 409 in-progress if executed before completion
            }
        }
        executor.shutdown();

        // All successful threads received the exact same reservation ID
        assertThat(reservationIds).hasSize(1);

        Slot updatedSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(updatedSlot.getReserved()).isEqualTo(2); // Only 2 reserved, not 2 * 10
    }

    @Test
    @DisplayName("Two instances of expiry job running concurrently: each reservation expired exactly once")
    void testTwoInstancesOfExpiryJobConcurrently() throws Exception {
        Instant startTime = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(1, ChronoUnit.HOURS);
        Slot slot = slotRepository.save(new Slot(testResource.getId(), startTime, endTime, 20));
        UUID slotId = slot.getId();

        // Create 10 expired PENDING reservations directly in DB
        Instant pastExpiry = Instant.now().minus(10, ChronoUnit.MINUTES);
        org.springframework.test.util.ReflectionTestUtils.setField(slot, "reserved", 10);
        slotRepository.save(slot);

        for (int i = 0; i < 10; i++) {
            Reservation r = new Reservation(slotId, "exp-user-" + i, 1, ReservationStatus.PENDING, pastExpiry);
            reservationRepository.save(r);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Integer> job1 = executor.submit(() -> {
            startLatch.await();
            return reservationExpiryJob.processBatch(10);
        });

        Future<Integer> job2 = executor.submit(() -> {
            startLatch.await();
            return reservationExpiryJob.processBatch(10);
        });

        startLatch.countDown();

        int count1 = job1.get(10, TimeUnit.SECONDS);
        int count2 = job2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Combined, exactly 10 reservations were expired
        assertThat(count1 + count2).isEqualTo(10);

        Slot updatedSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(updatedSlot.getReserved()).isEqualTo(0); // All 10 capacity released
    }

    @Test
    @DisplayName("Two expiry runners over many reservations across several slots: no deadlocks, each expired and released once")
    void testConcurrentExpiryAcrossMultipleSlotsNoDeadlock() throws Exception {
        int numSlots = 5;
        int reservationsPerSlot = 8;
        List<UUID> slotIds = new ArrayList<>();
        Instant pastExpiry = Instant.now().minus(10, ChronoUnit.MINUTES);

        for (int i = 0; i < numSlots; i++) {
            Instant start = Instant.now().plus(i + 1, ChronoUnit.DAYS);
            Instant end = start.plus(1, ChronoUnit.HOURS);
            Slot slot = new Slot(testResource.getId(), start, end, 20);
            slot.setReserved(reservationsPerSlot);
            slot = slotRepository.save(slot);
            slotIds.add(slot.getId());

            for (int j = 0; j < reservationsPerSlot; j++) {
                Reservation r = new Reservation(slot.getId(), "user-" + i + "-" + j, 1, ReservationStatus.PENDING, pastExpiry);
                reservationRepository.save(r);
            }
        }

        int totalReservations = numSlots * reservationsPerSlot;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Integer> job1 = executor.submit(() -> {
            startLatch.await();
            int total = 0;
            for (int k = 0; k < 5; k++) {
                total += reservationExpiryJob.processBatch(10);
            }
            return total;
        });

        Future<Integer> job2 = executor.submit(() -> {
            startLatch.await();
            int total = 0;
            for (int k = 0; k < 5; k++) {
                total += reservationExpiryJob.processBatch(10);
            }
            return total;
        });

        startLatch.countDown();
        int count1 = job1.get(15, TimeUnit.SECONDS);
        int count2 = job2.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(count1 + count2).isEqualTo(totalReservations);

        for (UUID sId : slotIds) {
            Slot updatedSlot = slotRepository.findById(sId).orElseThrow();
            assertThat(updatedSlot.getReserved()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Rollback test: failure after capacity update leaves reserved unchanged")
    void testRollbackAfterCapacityUpdateLeavesReservedUnchanged() {
        Instant startTime = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(1, ChronoUnit.HOURS);
        Slot slot = slotRepository.save(new Slot(testResource.getId(), startTime, endTime, 5));
        UUID slotId = slot.getId();

        TransactionTemplate template = new TransactionTemplate(transactionManager);

        try {
            template.execute(status -> {
                // Reserve 2 capacity
                int affected = slotRepository.reserve(slotId, 2);
                assertThat(affected).isEqualTo(1);

                // Simulate an unexpected runtime exception later in the same transaction
                throw new RuntimeException("Simulated catastrophic crash");
            });
        } catch (RuntimeException ex) {
            assertThat(ex.getMessage()).isEqualTo("Simulated catastrophic crash");
        }

        // Verify that the capacity increment was rolled back cleanly by PostgreSQL
        Slot currentSlot = slotRepository.findById(slotId).orElseThrow();
        assertThat(currentSlot.getReserved()).isEqualTo(0);
    }
}

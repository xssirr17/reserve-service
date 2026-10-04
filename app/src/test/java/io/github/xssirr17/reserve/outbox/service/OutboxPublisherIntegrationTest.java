package io.github.xssirr17.reserve.outbox.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    "spring.datasource.hikari.maximum-pool-size=20",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class OutboxPublisherIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ReservationProperties properties;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Two concurrent publishers publish each event exactly once")
    void testConcurrentPublishersPublishEachRowExactlyOnce() throws Exception {
        int totalEvents = 20;
        List<OutboxEvent> events = new ArrayList<>();
        for (int i = 0; i < totalEvents; i++) {
            events.add(new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "ReservationCreated", "{}"));
        }
        outboxEventRepository.saveAll(events);

        ConcurrentHashMap<UUID, AtomicInteger> publishCounts = new ConcurrentHashMap<>();
        EventPublisher countingPublisher = event -> {
            publishCounts.computeIfAbsent(event.getId(), k -> new AtomicInteger(0)).incrementAndGet();
            try {
                Thread.sleep(10); // simulate network latency
            } catch (InterruptedException ignored) {}
        };

        OutboxPublisher publisher1 = new OutboxPublisher(outboxEventRepository, countingPublisher, properties, clock, transactionManager);
        OutboxPublisher publisher2 = new OutboxPublisher(outboxEventRepository, countingPublisher, properties, clock, transactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            try {
                latch.await();
                publisher1.processBatch(20);
            } catch (Exception ignored) {}
        });

        executor.submit(() -> {
            try {
                latch.await();
                publisher2.processBatch(20);
            } catch (Exception ignored) {}
        });

        latch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(finished).isTrue();

        // Verify each event was published exactly once
        assertThat(publishCounts).hasSize(totalEvents);
        for (AtomicInteger count : publishCounts.values()) {
            assertThat(count.get()).isEqualTo(1);
        }

        List<OutboxEvent> all = outboxEventRepository.findAll();
        assertThat(all).hasSize(totalEvents);
        assertThat(all).allMatch(e -> "PUBLISHED".equals(e.getStatus()));
    }

    @Test
    @DisplayName("Expired lease is reclaimed by next publisher run")
    void testExpiredLeaseIsReclaimed() {
        OutboxEvent event = new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "ReservationCreated", "{}");
        // Simulate a crashed publisher that locked the event 2 minutes ago with lease 30s
        event.setLockedUntil(clock.instant().minus(Duration.ofMinutes(1)));
        event = outboxEventRepository.save(event);

        AtomicInteger publishedCount = new AtomicInteger(0);
        EventPublisher publisherStub = e -> publishedCount.incrementAndGet();

        OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, publisherStub, properties, clock, transactionManager);
        int processed = publisher.processBatch(10);

        assertThat(processed).isEqualTo(1);
        assertThat(publishedCount.get()).isEqualTo(1);

        OutboxEvent updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("A poison row does not block remaining events in the batch")
    void testPoisonRowDoesNotBlockQueue() {
        OutboxEvent poison = new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "PoisonEvent", "{}");
        OutboxEvent healthy1 = new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "Healthy1", "{}");
        OutboxEvent healthy2 = new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "Healthy2", "{}");

        poison = outboxEventRepository.save(poison);
        healthy1 = outboxEventRepository.save(healthy1);
        healthy2 = outboxEventRepository.save(healthy2);

        UUID poisonId = poison.getId();

        EventPublisher publisherWithPoison = event -> {
            if (event.getId().equals(poisonId)) {
                throw new RuntimeException("Poison pill broker reject");
            }
        };

        OutboxPublisher publisher = new OutboxPublisher(outboxEventRepository, publisherWithPoison, properties, clock, transactionManager);
        int processed = publisher.processBatch(10);

        assertThat(processed).isEqualTo(3);

        OutboxEvent updatedPoison = outboxEventRepository.findById(poisonId).orElseThrow();
        assertThat(updatedPoison.getStatus()).isEqualTo("PENDING");
        assertThat(updatedPoison.getAttempts()).isEqualTo(1);
        assertThat(updatedPoison.getLockedUntil()).isAfter(clock.instant());

        OutboxEvent updatedHealthy1 = outboxEventRepository.findById(healthy1.getId()).orElseThrow();
        assertThat(updatedHealthy1.getStatus()).isEqualTo("PUBLISHED");

        OutboxEvent updatedHealthy2 = outboxEventRepository.findById(healthy2.getId()).orElseThrow();
        assertThat(updatedHealthy2.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("publishBatch scheduled entry point publishes due outbox events")
    void testPublishBatchScheduledEntryPoint() {
        OutboxEvent event = new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "ReservationCreated", "{}");
        event = outboxEventRepository.save(event);

        outboxPublisher.publishBatch();

        OutboxEvent published = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published.getPublishedAt()).isNotNull();
    }
}

package io.github.xssirr17.reserve.outbox.service;

import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.outbox.domain.OutboxEvent;
import io.github.xssirr17.reserve.outbox.domain.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private ReservationProperties properties;
    private Clock fixedClock;
    private OutboxPublisher outboxPublisher;

    private final AtomicBoolean txActiveDuringPublish = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        properties = new ReservationProperties();
        fixedClock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

        PlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };

        EventPublisher eventPublisher = event -> {
            txActiveDuringPublish.set(TransactionSynchronizationManager.isActualTransactionActive());
        };

        outboxPublisher = new OutboxPublisher(
            outboxEventRepository,
            eventPublisher,
            properties,
            fixedClock,
            txManager
        );
    }

    @Test
    @DisplayName("External publish must NOT hold an active database transaction open")
    void testExternalPublishRunsOutsideTransaction() {
        OutboxEvent event = new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "ReservationCreated", "{}");
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", UUID.randomUUID());

        when(outboxEventRepository.findDueEventsForUpdateSkipLocked(any(Instant.class), eq(50)))
            .thenReturn(List.of(event));
        when(outboxEventRepository.saveAll(any())).thenReturn(List.of(event));
        when(outboxEventRepository.findById(event.getId())).thenReturn(Optional.of(event));

        int processed = outboxPublisher.processBatch(50);

        assertThat(processed).isEqualTo(1);
        assertThat(txActiveDuringPublish.get()).isFalse();
        assertThat(event.getStatus()).isEqualTo("PUBLISHED");
        assertThat(event.getPublishedAt()).isEqualTo(fixedClock.instant());
        assertThat(event.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("Failed publish increments attempts and reschedules with lockedUntil backoff")
    void testFailedPublishIncrementsAttemptsAndSetsBackoff() {
        EventPublisher failingPublisher = event -> {
            throw new RuntimeException("Simulated broker failure");
        };

        PlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() { return new Object(); }
            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
            @Override
            protected void doCommit(DefaultTransactionStatus status) {}
            @Override
            protected void doRollback(DefaultTransactionStatus status) {}
        };

        OutboxPublisher publisher = new OutboxPublisher(
            outboxEventRepository,
            failingPublisher,
            properties,
            fixedClock,
            txManager
        );

        OutboxEvent event = new OutboxEvent("RESERVATION", UUID.randomUUID().toString(), "ReservationCreated", "{}");
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", UUID.randomUUID());

        when(outboxEventRepository.findDueEventsForUpdateSkipLocked(any(Instant.class), eq(50)))
            .thenReturn(List.of(event));
        when(outboxEventRepository.saveAll(any())).thenReturn(List.of(event));
        when(outboxEventRepository.findById(event.getId())).thenReturn(Optional.of(event));

        int processed = publisher.processBatch(50);

        assertThat(processed).isEqualTo(1);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getLockedUntil()).isEqualTo(fixedClock.instant().plus(properties.getOutbox().getRetryBackoff()));
    }
}

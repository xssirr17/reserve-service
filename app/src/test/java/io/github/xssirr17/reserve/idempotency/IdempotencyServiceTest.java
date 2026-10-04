package io.github.xssirr17.reserve.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.error.IdempotencyConflictException;
import io.github.xssirr17.reserve.common.error.IdempotencyInProgressException;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyClaimManager claimManager;
    @Mock
    private IdempotencyKeyRepository repository;
    @Mock
    private ReservationMetrics metrics;

    private ReservationProperties properties;
    private ObjectMapper objectMapper;
    private IdempotencyService idempotencyService;

    record SampleRequest(String name, int count) {}
    record SampleResponse(String id, String status) {}

    @BeforeEach
    void setUp() {
        properties = new ReservationProperties();
        properties.getIdempotency().setInProgressTimeout(Duration.ofMinutes(2));
        objectMapper = new ObjectMapper();

        org.springframework.transaction.PlatformTransactionManager txManager =
            new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() { return new Object(); }
                @Override
                protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
                @Override
                protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                @Override
                protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            };

        idempotencyService = new IdempotencyService(
            claimManager,
            repository,
            properties,
            metrics,
            objectMapper,
            txManager
        );
    }

    @Test
    @DisplayName("execute: claims new key, executes business operation and persists COMPLETED response")
    void testExecuteNewKeySuccess() {
        String scope = "user:u1:POST:/api";
        String key = "idem-key-1";
        SampleRequest request = new SampleRequest("meeting", 4);

        when(claimManager.tryClaimKey(eq(scope), eq(key), any())).thenReturn(true);

        IdempotencyKey keyEntity = new IdempotencyKey(scope, key, "somehash", IdempotencyStatus.IN_PROGRESS);
        when(repository.findById(new IdempotencyKeyId(scope, key))).thenReturn(Optional.of(keyEntity));

        AtomicInteger callCount = new AtomicInteger(0);
        SampleResponse response = idempotencyService.execute(scope, key, request, SampleResponse.class, () -> {
            callCount.incrementAndGet();
            return new SampleResponse("res-1", "CREATED");
        });

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(response.id()).isEqualTo("res-1");
        assertThat(keyEntity.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(keyEntity.getResponseBody()).contains("res-1");
        verify(repository).save(keyEntity);
    }

    @Test
    @DisplayName("execute: replays stored response when same key and same hash are already COMPLETED")
    void testReplayStoredResponse() throws Exception {
        String scope = "user:u1:POST:/api";
        String key = "idem-key-1";
        SampleRequest request = new SampleRequest("meeting", 4);
        String expectedHash = idempotencyService.computeHash(request);

        when(claimManager.tryClaimKey(eq(scope), eq(key), eq(expectedHash))).thenReturn(false);

        IdempotencyKey completedKey = new IdempotencyKey(scope, key, expectedHash, IdempotencyStatus.COMPLETED);
        completedKey.setResponseBody(objectMapper.writeValueAsString(new SampleResponse("res-cached", "OK")));
        when(claimManager.findKey(scope, key)).thenReturn(Optional.of(completedKey));

        AtomicInteger callCount = new AtomicInteger(0);
        SampleResponse response = idempotencyService.execute(scope, key, request, SampleResponse.class, () -> {
            callCount.incrementAndGet();
            return new SampleResponse("res-new", "OK");
        });

        assertThat(callCount.get()).isEqualTo(0); // Business logic was NOT executed
        assertThat(response.id()).isEqualTo("res-cached");
        verify(metrics).incrementIdempotencyReplays();
    }

    @Test
    @DisplayName("execute: throws IdempotencyConflictException (422) when same key has different request hash")
    void testDifferentHashThrows422() {
        String scope = "user:u1:POST:/api";
        String key = "idem-key-1";
        SampleRequest request1 = new SampleRequest("original", 1);
        SampleRequest request2 = new SampleRequest("modified", 2);

        String originalHash = idempotencyService.computeHash(request1);
        String newHash = idempotencyService.computeHash(request2);

        when(claimManager.tryClaimKey(eq(scope), eq(key), eq(newHash))).thenReturn(false);

        IdempotencyKey completedKey = new IdempotencyKey(scope, key, originalHash, IdempotencyStatus.COMPLETED);
        when(claimManager.findKey(scope, key)).thenReturn(Optional.of(completedKey));

        assertThatThrownBy(() -> idempotencyService.execute(scope, key, request2, SampleResponse.class, () -> new SampleResponse("x", "y")))
            .isInstanceOf(IdempotencyConflictException.class)
            .hasMessageContaining("different request payload");
    }

    @Test
    @DisplayName("execute: throws IdempotencyInProgressException (409) when concurrent request is in progress")
    void testConcurrentInProgressThrows409() {
        String scope = "user:u1:POST:/api";
        String key = "idem-key-1";
        SampleRequest request = new SampleRequest("meeting", 4);
        String hash = idempotencyService.computeHash(request);

        when(claimManager.tryClaimKey(eq(scope), eq(key), eq(hash))).thenReturn(false);

        IdempotencyKey inProgressKey = new IdempotencyKey(scope, key, hash, IdempotencyStatus.IN_PROGRESS);
        when(claimManager.findKey(scope, key)).thenReturn(Optional.of(inProgressKey));
        when(claimManager.tryReclaimExpired(eq(scope), eq(key), eq(hash), any())).thenReturn(false);

        assertThatThrownBy(() -> idempotencyService.execute(scope, key, request, SampleResponse.class, () -> new SampleResponse("x", "y")))
            .isInstanceOf(IdempotencyInProgressException.class)
            .satisfies(ex -> {
                IdempotencyInProgressException ipe = (IdempotencyInProgressException) ex;
                assertThat(ipe.getRetryAfterSeconds()).isEqualTo(2);
            });
    }

    @Test
    @DisplayName("execute: reclaims expired IN_PROGRESS request and proceeds with execution")
    void testReclaimsStaleInProgress() {
        String scope = "user:u1:POST:/api";
        String key = "idem-key-1";
        SampleRequest request = new SampleRequest("meeting", 4);
        String hash = idempotencyService.computeHash(request);

        when(claimManager.tryClaimKey(eq(scope), eq(key), eq(hash))).thenReturn(false);

        IdempotencyKey staleKey = new IdempotencyKey(scope, key, hash, IdempotencyStatus.IN_PROGRESS);
        when(claimManager.findKey(scope, key)).thenReturn(Optional.of(staleKey));
        when(claimManager.tryReclaimExpired(eq(scope), eq(key), eq(hash), any())).thenReturn(true);

        when(repository.findById(new IdempotencyKeyId(scope, key))).thenReturn(Optional.of(staleKey));

        SampleResponse response = idempotencyService.execute(scope, key, request, SampleResponse.class,
            () -> new SampleResponse("reclaimed-res", "OK"));

        assertThat(response.id()).isEqualTo("reclaimed-res");
        assertThat(staleKey.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("execute: releases claim in separate transaction if business operation fails")
    void testReleasesClaimOnFailure() {
        String scope = "user:u1:POST:/api";
        String key = "idem-key-1";
        SampleRequest request = new SampleRequest("meeting", 4);

        when(claimManager.tryClaimKey(eq(scope), eq(key), any())).thenReturn(true);

        assertThatThrownBy(() -> idempotencyService.execute(scope, key, request, SampleResponse.class, () -> {
            throw new RuntimeException("Simulated business error");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated business error");

        verify(claimManager).releaseClaim(scope, key);
    }
}

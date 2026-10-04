package io.github.xssirr17.reserve.slot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import io.github.xssirr17.reserve.slot.dto.SlotResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvailabilityCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ReservationMetrics metrics;

    private ReservationProperties properties;
    private ObjectMapper objectMapper;
    private AvailabilityCacheService availabilityCacheService;

    @BeforeEach
    void setUp() {
        properties = new ReservationProperties();
        properties.getCache().setWaitTimeout(Duration.ofSeconds(2));
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        availabilityCacheService = new AvailabilityCacheService(
            redisTemplate,
            properties,
            metrics,
            objectMapper
        );
    }

    @Test
    @DisplayName("N concurrent callers on a missing cache key: DB fallback runs exactly once")
    void testConcurrentCallersFallbackRunsOnce() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null); // Cache miss

        UUID resourceId = UUID.randomUUID();
        Instant from = Instant.now();
        Instant to = from.plusSeconds(3600);
        PageRequest pageable = PageRequest.of(0, 10);

        SlotResponse sampleSlot = new SlotResponse(UUID.randomUUID(), resourceId, from, to, 10, 0, Instant.now());
        Page<SlotResponse> expectedPage = new PageImpl<>(List.of(sampleSlot), pageable, 1);

        AtomicInteger dbCallCount = new AtomicInteger(0);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Page<SlotResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return availabilityCacheService.getAvailability(resourceId, from, to, pageable, () -> {
                    dbCallCount.incrementAndGet();
                    try {
                        Thread.sleep(60); // simulate slow DB call
                    } catch (InterruptedException ignored) {}
                    return expectedPage;
                });
            }));
        }

        startLatch.countDown();
        for (Future<Page<SlotResponse>> future : futures) {
            Page<SlotResponse> result = future.get(5, TimeUnit.SECONDS);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).id()).isEqualTo(sampleSlot.id());
        }
        executor.shutdown();

        // Exactly one DB fallback execution!
        assertThat(dbCallCount.get()).isEqualTo(1);
        // In-flight map is completely cleared
        assertThat(availabilityCacheService.getInFlightCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Fallback exception is propagated to all waiting callers and in-flight map is cleaned up")
    void testFallbackExceptionPropagatesAndCleansMap() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        UUID resourceId = UUID.randomUUID();
        Instant from = Instant.now();
        Instant to = from.plusSeconds(3600);
        PageRequest pageable = PageRequest.of(0, 10);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Page<SlotResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return availabilityCacheService.getAvailability(resourceId, from, to, pageable, () -> {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException ignored) {}
                    throw new IllegalStateException("Database connectivity failure");
                });
            }));
        }

        startLatch.countDown();
        for (Future<Page<SlotResponse>> future : futures) {
            assertThatThrownBy(() -> {
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }).isInstanceOf(IllegalStateException.class).hasMessageContaining("Database connectivity failure");
        }
        executor.shutdown();

        // Ensure no leaked entries in the in-flight map
        assertThat(availabilityCacheService.getInFlightCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Redis throwing error degrades gracefully to DB result")
    void testRedisThrowingDegradesToDb() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Redis connection refused"));

        UUID resourceId = UUID.randomUUID();
        Instant from = Instant.now();
        Instant to = from.plusSeconds(3600);
        PageRequest pageable = PageRequest.of(0, 10);

        SlotResponse sampleSlot = new SlotResponse(UUID.randomUUID(), resourceId, from, to, 10, 0, Instant.now());
        Page<SlotResponse> expectedPage = new PageImpl<>(List.of(sampleSlot), pageable, 1);

        Page<SlotResponse> result = availabilityCacheService.getAvailability(
            resourceId, from, to, pageable, () -> expectedPage
        );

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(availabilityCacheService.getInFlightCount()).isEqualTo(0);
    }
}

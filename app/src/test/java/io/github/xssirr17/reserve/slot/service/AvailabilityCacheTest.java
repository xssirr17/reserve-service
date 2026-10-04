package io.github.xssirr17.reserve.slot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvailabilityCacheTest {

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
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        availabilityCacheService = new AvailabilityCacheService(
            redisTemplate,
            properties,
            metrics,
            objectMapper
        );
    }

    @Test
    @DisplayName("getAvailability: returns cached response without calling DB fallback on cache hit")
    void testCacheHit() throws Exception {
        UUID resourceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-05T09:00:00Z");
        Instant to = Instant.parse("2026-10-05T17:00:00Z");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("res:ver:" + resourceId)).thenReturn("1");

        SlotResponse slot = new SlotResponse(UUID.randomUUID(), resourceId, from, to, 10, 2, Instant.now());
        SlotPageCacheDto cachedDto = new SlotPageCacheDto(List.of(slot), 0, 20, 1);
        String cachedJson = objectMapper.writeValueAsString(cachedDto);

        when(valueOperations.get(contains("avail:v1:"))).thenReturn(cachedJson);

        AtomicInteger dbCallCount = new AtomicInteger(0);
        Page<SlotResponse> result = availabilityCacheService.getAvailability(
            resourceId, from, to, PageRequest.of(0, 20), () -> {
                dbCallCount.incrementAndGet();
                return Page.empty();
            }
        );

        assertThat(dbCallCount.get()).isEqualTo(0);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(slot.id());
        verify(metrics).incrementCacheHits();
    }

    @Test
    @DisplayName("getAvailability: falls back to DB and writes to Redis on cache miss")
    void testCacheMissPopulatesRedis() {
        UUID resourceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-05T09:00:00Z");
        Instant to = Instant.parse("2026-10-05T17:00:00Z");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        SlotResponse slot = new SlotResponse(UUID.randomUUID(), resourceId, from, to, 10, 0, Instant.now());
        Page<SlotResponse> dbPage = new PageImpl<>(List.of(slot), PageRequest.of(0, 20), 1);

        AtomicInteger dbCallCount = new AtomicInteger(0);
        Page<SlotResponse> result = availabilityCacheService.getAvailability(
            resourceId, from, to, PageRequest.of(0, 20), () -> {
                dbCallCount.incrementAndGet();
                return dbPage;
            }
        );

        assertThat(dbCallCount.get()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        verify(metrics).incrementCacheMisses();
        verify(valueOperations).set(contains("avail:v1:"), anyString(), any());
    }

    @Test
    @DisplayName("getAvailability: gracefully degrades to DB query when Redis throws connection error")
    void testRedisThrowsGracefulFallback() {
        UUID resourceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-05T09:00:00Z");
        Instant to = Instant.parse("2026-10-05T17:00:00Z");

        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        SlotResponse slot = new SlotResponse(UUID.randomUUID(), resourceId, from, to, 10, 0, Instant.now());
        Page<SlotResponse> dbPage = new PageImpl<>(List.of(slot), PageRequest.of(0, 20), 1);

        Page<SlotResponse> result = availabilityCacheService.getAvailability(
            resourceId, from, to, PageRequest.of(0, 20), () -> dbPage
        );

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        verify(metrics).incrementCacheMisses();
    }
}

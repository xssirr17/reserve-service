package io.github.xssirr17.reserve.slot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import io.github.xssirr17.reserve.slot.dto.SlotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Service
public class AvailabilityCacheService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityCacheService.class);

    private final StringRedisTemplate redisTemplate;
    private final ReservationProperties properties;
    private final ReservationMetrics metrics;
    private final ObjectMapper objectMapper;

    // Single-flight stampede protection map
    private final ConcurrentHashMap<String, CompletableFuture<Page<SlotResponse>>> inFlightRequests = new ConcurrentHashMap<>();

    public AvailabilityCacheService(StringRedisTemplate redisTemplate,
                                    ReservationProperties properties,
                                    ReservationMetrics metrics,
                                    ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    public Page<SlotResponse> getAvailability(UUID resourceId,
                                              Instant from,
                                              Instant to,
                                              Pageable pageable,
                                              Supplier<Page<SlotResponse>> dbFallback) {
        String version = getResourceVersion(resourceId);
        String cacheKey = String.format("avail:v1:%s:v%s:%s:%s:%d:%d",
            resourceId, version, from, to, pageable.getPageNumber(), pageable.getPageSize());

        // 1. Try reading from cache
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (cachedJson != null) {
                metrics.incrementCacheHits();
                SlotPageCacheDto dto = objectMapper.readValue(cachedJson, SlotPageCacheDto.class);
                return dto.toPage();
            }
        } catch (Exception ex) {
            log.warn("Redis read failed for key {}, degrading gracefully to DB: {}", cacheKey, ex.getMessage());
        }

        metrics.incrementCacheMisses();

        // 2. Single-flight stampede protection: collapse concurrent requests for identical key
        CompletableFuture<Page<SlotResponse>> future = inFlightRequests.computeIfAbsent(cacheKey, k -> CompletableFuture.supplyAsync(() -> {
            try {
                Page<SlotResponse> dbResult = dbFallback.get();
                putInCache(k, dbResult);
                return dbResult;
            } finally {
                inFlightRequests.remove(k);
            }
        }));

        try {
            return future.join();
        } catch (Exception ex) {
            inFlightRequests.remove(cacheKey);
            log.warn("Single-flight execution failed for key {}, falling back to direct DB read: {}", cacheKey, ex.getMessage());
            return dbFallback.get();
        }
    }

    public void invalidateResourceAfterCommit(UUID resourceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    incrementResourceVersion(resourceId);
                }
            });
        } else {
            incrementResourceVersion(resourceId);
        }
    }

    private String getResourceVersion(UUID resourceId) {
        try {
            String ver = redisTemplate.opsForValue().get("res:ver:" + resourceId);
            return ver != null ? ver : "0";
        } catch (Exception ex) {
            log.warn("Failed to get cache version for resource {}: {}", resourceId, ex.getMessage());
            return "0";
        }
    }

    private void incrementResourceVersion(UUID resourceId) {
        try {
            redisTemplate.opsForValue().increment("res:ver:" + resourceId);
            log.debug("Incremented cache version for resource {}", resourceId);
        } catch (Exception ex) {
            log.warn("Failed to increment cache version for resource {}: {}", resourceId, ex.getMessage());
        }
    }

    private void putInCache(String cacheKey, Page<SlotResponse> page) {
        try {
            SlotPageCacheDto dto = SlotPageCacheDto.from(page);
            String json = objectMapper.writeValueAsString(dto);

            long baseTtl = properties.getCache().getTtl().toSeconds();
            long jitter = ThreadLocalRandom.current().nextLong(properties.getCache().getJitterMaxSeconds() + 1);
            Duration ttl = Duration.ofSeconds(baseTtl + jitter);

            redisTemplate.opsForValue().set(cacheKey, json, ttl);
        } catch (Exception ex) {
            log.warn("Redis write failed for key {}: {}", cacheKey, ex.getMessage());
        }
    }
}

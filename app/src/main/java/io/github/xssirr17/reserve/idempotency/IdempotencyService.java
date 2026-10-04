package io.github.xssirr17.reserve.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.xssirr17.reserve.common.config.ReservationProperties;
import io.github.xssirr17.reserve.common.error.IdempotencyConflictException;
import io.github.xssirr17.reserve.common.error.IdempotencyInProgressException;
import io.github.xssirr17.reserve.common.metrics.ReservationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyClaimManager claimManager;
    private final IdempotencyKeyRepository repository;
    private final ReservationProperties properties;
    private final ReservationMetrics metrics;
    private final ObjectMapper canonicalMapper;
    private final ObjectMapper responseMapper;

    public IdempotencyService(IdempotencyClaimManager claimManager,
                              IdempotencyKeyRepository repository,
                              ReservationProperties properties,
                              ReservationMetrics metrics,
                              ObjectMapper objectMapper) {
        this.claimManager = claimManager;
        this.repository = repository;
        this.properties = properties;
        this.metrics = metrics;
        this.responseMapper = objectMapper;
        this.canonicalMapper = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .findAndAddModules()
            .build();
    }

    public <T> T execute(String scope, String key, Object requestBody, Class<T> responseType, Supplier<T> businessOperation) {
        if (key == null || key.isBlank()) {
            return businessOperation.get();
        }

        String requestHash = computeHash(requestBody);

        boolean claimed = claimManager.tryClaimKey(scope, key, requestHash);
        if (!claimed) {
            Optional<IdempotencyKey> existingOpt = claimManager.findKey(scope, key);
            if (existingOpt.isPresent()) {
                IdempotencyKey existing = existingOpt.get();

                if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                    if (!existing.getRequestHash().equals(requestHash)) {
                        log.warn("Idempotency key collision with mismatched hash: scope={}, key={}", scope, key);
                        throw new IdempotencyConflictException(
                            String.format("Idempotency key '%s' has already been used with a different request payload in scope '%s'",
                                key, scope)
                        );
                    }
                    metrics.incrementIdempotencyReplays();
                    log.info("Replaying stored response for idempotent key: scope={}, key={}", scope, key);
                    try {
                        return responseMapper.readValue(existing.getResponseBody(), responseType);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Failed to deserialize cached response for key: " + key, e);
                    }
                } else if (existing.getStatus() == IdempotencyStatus.IN_PROGRESS) {
                    boolean reclaimed = claimManager.tryReclaimExpired(
                        scope, key, requestHash, properties.getIdempotency().getInProgressTimeout()
                    );
                    if (!reclaimed) {
                        log.warn("Idempotent request in progress: scope={}, key={}", scope, key);
                        throw new IdempotencyInProgressException(
                            String.format("A request with idempotency key '%s' is currently in progress", key), 2
                        );
                    }
                    log.info("Reclaimed stale IN_PROGRESS idempotency key: scope={}, key={}", scope, key);
                }
            }
        }

        try {
            return executeAndStore(scope, key, responseType, businessOperation);
        } catch (Exception ex) {
            log.warn("Business operation failed for idempotency key {}/{}, releasing claim: {}",
                scope, key, ex.getMessage());
            claimManager.releaseClaim(scope, key);
            throw ex;
        }
    }

    @Transactional
    public <T> T executeAndStore(String scope, String key, Class<T> responseType, Supplier<T> businessOperation) {
        T result = businessOperation.get();

        try {
            String json = responseMapper.writeValueAsString(result);
            IdempotencyKey keyEntity = repository.findById(new IdempotencyKeyId(scope, key))
                .orElseThrow(() -> new IllegalStateException("Claimed idempotency key not found: " + key));

            keyEntity.setStatus(IdempotencyStatus.COMPLETED);
            keyEntity.setResponseStatus(200);
            keyEntity.setResponseBody(json);
            repository.save(keyEntity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response for idempotency key: " + key, e);
        }

        return result;
    }

    public String computeHash(Object requestBody) {
        try {
            String canonicalJson = canonicalMapper.writeValueAsString(requestBody != null ? requestBody : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to compute canonical request hash", e);
        }
    }
}

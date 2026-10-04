package io.github.xssirr17.reserve.idempotency;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class IdempotencyClaimManager {

    private final IdempotencyKeyRepository repository;
    private final Clock clock;

    public IdempotencyClaimManager(IdempotencyKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaimKey(String scope, String key, String requestHash) {
        int inserted = repository.tryInsertInProgress(scope, key, requestHash, clock.instant());
        return inserted > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyKey> findKey(String scope, String key) {
        return repository.findByScopeAndKey(scope, key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReclaimExpired(String scope, String key, String requestHash, Duration timeout) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(timeout);
        int updated = repository.tryReclaimExpired(scope, key, requestHash, now, cutoff);
        return updated > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(String scope, String key) {
        repository.releaseClaim(scope, key);
    }
}

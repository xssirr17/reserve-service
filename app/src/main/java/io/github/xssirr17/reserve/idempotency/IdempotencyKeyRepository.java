package io.github.xssirr17.reserve.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    default Optional<IdempotencyKey> findByScopeAndKey(String scope, String idempotencyKey) {
        return findById(new IdempotencyKeyId(scope, idempotencyKey));
    }
}

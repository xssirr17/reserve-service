package io.github.xssirr17.reserve.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    default Optional<IdempotencyKey> findByScopeAndKey(String scope, String idempotencyKey) {
        return findById(new IdempotencyKeyId(scope, idempotencyKey));
    }

    @Modifying
    @Query(value = """
        INSERT INTO idempotency_keys (scope, idempotency_key, request_hash, status, created_at)
        VALUES (:scope, :key, :hash, 'IN_PROGRESS', :createdAt)
        ON CONFLICT (scope, idempotency_key) DO NOTHING
    """, nativeQuery = true)
    int tryInsertInProgress(
        @Param("scope") String scope,
        @Param("key") String key,
        @Param("hash") String hash,
        @Param("createdAt") Instant createdAt
    );

    @Modifying
    @Query(value = """
        UPDATE idempotency_keys
        SET request_hash = :hash, created_at = :now, status = 'IN_PROGRESS'
        WHERE scope = :scope
          AND idempotency_key = :key
          AND status = 'IN_PROGRESS'
          AND created_at < :cutoff
    """, nativeQuery = true)
    int tryReclaimExpired(
        @Param("scope") String scope,
        @Param("key") String key,
        @Param("hash") String hash,
        @Param("now") Instant now,
        @Param("cutoff") Instant cutoff
    );

    @Modifying
    @Query("""
        DELETE FROM IdempotencyKey k
        WHERE k.id.scope = :scope
          AND k.id.idempotencyKey = :key
          AND k.status = io.github.xssirr17.reserve.idempotency.IdempotencyStatus.IN_PROGRESS
    """)
    int releaseClaim(@Param("scope") String scope, @Param("key") String key);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}

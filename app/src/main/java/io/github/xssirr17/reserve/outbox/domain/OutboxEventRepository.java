package io.github.xssirr17.reserve.outbox.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
        SELECT * FROM outbox_events
        WHERE status = 'PENDING'
          AND (locked_until IS NULL OR locked_until <= :now)
        ORDER BY created_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
    """, nativeQuery = true)
    List<OutboxEvent> findDueEventsForUpdateSkipLocked(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
        SELECT * FROM outbox_events
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
    """, nativeQuery = true)
    List<OutboxEvent> findUnpublishedForUpdateSkipLocked(@Param("limit") int limit);
}

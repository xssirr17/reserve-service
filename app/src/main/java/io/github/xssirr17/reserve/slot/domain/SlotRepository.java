package io.github.xssirr17.reserve.slot.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {

    @Query("""
        SELECT s FROM Slot s
        WHERE s.resourceId = :resourceId
          AND s.startTime >= :from
          AND s.endTime <= :to
    """)
    Page<Slot> findByResourceIdAndStartTimeGreaterThanEqualAndEndTimeLessThanEqual(
        @Param("resourceId") UUID resourceId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );

    @Query("""
        SELECT s FROM Slot s
        WHERE s.resourceId = :resourceId
          AND s.startTime >= :from
          AND s.endTime <= :to
    """)
    Page<Slot> findByResourceIdAndTimeRange(
        @Param("resourceId") UUID resourceId,
        @Param("from") Instant from,
        @Param("to") Instant to,
        Pageable pageable
    );

    @Query("""
        SELECT COUNT(s) > 0 FROM Slot s
        WHERE s.resourceId = :resourceId
          AND s.startTime < :endTime
          AND s.endTime > :startTime
    """)
    boolean hasOverlappingSlot(
        @Param("resourceId") UUID resourceId,
        @Param("startTime") Instant startTime,
        @Param("endTime") Instant endTime
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Slot s
        SET s.reserved = s.reserved + :quantity,
            s.version = s.version + 1
        WHERE s.id = :slotId
          AND s.reserved + :quantity <= s.capacity
    """)
    int reserve(@Param("slotId") UUID slotId, @Param("quantity") int quantity);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Slot s
        SET s.reserved = s.reserved - :quantity,
            s.version = s.version + 1
        WHERE s.id = :slotId
          AND s.reserved >= :quantity
    """)
    int release(@Param("slotId") UUID slotId, @Param("quantity") int quantity);
}

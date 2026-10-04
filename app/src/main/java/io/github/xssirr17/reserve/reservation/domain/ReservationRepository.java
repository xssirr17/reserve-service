package io.github.xssirr17.reserve.reservation.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    Page<Reservation> findByUserId(String userId, Pageable pageable);

    @Query(value = """
        SELECT * FROM reservations
        WHERE status = 'PENDING'
          AND expires_at < :now
        ORDER BY expires_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
    """, nativeQuery = true)
    List<Reservation> findExpiredPendingForUpdateSkipLocked(
        @Param("now") Instant now,
        @Param("limit") int limit
    );
}

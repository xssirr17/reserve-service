package io.github.xssirr17.reserve.reservation.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Page<Reservation> findByUserId(String userId, Pageable pageable);

    @Query("""
        SELECT r FROM Reservation r
        WHERE r.status = io.github.xssirr17.reserve.reservation.domain.ReservationStatus.PENDING
          AND r.expiresAt < :now
    """)
    List<Reservation> findExpiredPending(@Param("now") Instant now, Pageable pageable);

    List<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant now, Pageable pageable);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("""
        SELECT r FROM Reservation r
        WHERE r.status = io.github.xssirr17.reserve.reservation.domain.ReservationStatus.PENDING
          AND r.expiresAt < :now
    """)
    List<Reservation> findExpiredPendingWithLock(@Param("now") Instant now, Pageable pageable);
}

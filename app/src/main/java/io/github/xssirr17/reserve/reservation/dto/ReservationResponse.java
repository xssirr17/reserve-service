package io.github.xssirr17.reserve.reservation.dto;

import io.github.xssirr17.reserve.reservation.domain.Reservation;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID id,
    UUID slotId,
    String userId,
    int quantity,
    ReservationStatus status,
    Instant expiresAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
            reservation.getId(),
            reservation.getSlotId(),
            reservation.getUserId(),
            reservation.getQuantity(),
            reservation.getStatus(),
            reservation.getExpiresAt(),
            reservation.getCreatedAt(),
            reservation.getUpdatedAt()
        );
    }
}

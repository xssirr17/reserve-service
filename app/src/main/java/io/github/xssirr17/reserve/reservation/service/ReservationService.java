package io.github.xssirr17.reserve.reservation.service;

import io.github.xssirr17.reserve.reservation.dto.CreateReservationRequest;
import io.github.xssirr17.reserve.reservation.dto.ReservationResponse;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ReservationService {

    public ReservationResponse createReservation(CreateReservationRequest request, String idempotencyKey) {
        throw new UnsupportedOperationException("TODO");
    }

    public ReservationResponse getReservation(UUID id) {
        throw new UnsupportedOperationException("TODO");
    }

    public ReservationResponse confirmReservation(UUID id) {
        throw new UnsupportedOperationException("TODO");
    }

    public ReservationResponse cancelReservation(UUID id) {
        throw new UnsupportedOperationException("TODO");
    }
}

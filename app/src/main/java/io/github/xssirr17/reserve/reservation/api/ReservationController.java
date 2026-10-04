package io.github.xssirr17.reserve.reservation.api;

import io.github.xssirr17.reserve.idempotency.IdempotentResponse;
import io.github.xssirr17.reserve.reservation.dto.CreateReservationRequest;
import io.github.xssirr17.reserve.reservation.dto.ReservationResponse;
import io.github.xssirr17.reserve.reservation.service.ReservationService;
import io.github.xssirr17.reserve.saga.ReservationSagaCoordinator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
@Validated
public class ReservationController {

    private final ReservationService reservationService;
    private final ReservationSagaCoordinator sagaCoordinator;

    public ReservationController(ReservationService reservationService,
                                 ReservationSagaCoordinator sagaCoordinator) {
        this.reservationService = reservationService;
        this.sagaCoordinator = sagaCoordinator;
    }

    /**
     * Create a reservation for a given slot.
     * Idempotency-Key header is required on create per system specification.
     *
     * @param idempotencyKey client idempotency key
     * @param request        reservation payload
     * @return 201 Created with Location header and ReservationResponse (replayed on idempotent retries)
     */
    @PostMapping
    public ResponseEntity<ReservationResponse> createReservation(
        @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
        @RequestBody @Valid CreateReservationRequest request
    ) {
        IdempotentResponse<ReservationResponse> response = reservationService.createReservation(request, idempotencyKey);
        var builder = ResponseEntity.status(response.statusCode());
        if (response.headers() != null) {
            response.headers().forEach(builder::header);
        }
        return builder.body(response.body());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable UUID id) {
        return ResponseEntity.ok(reservationService.getReservation(id));
    }

    @GetMapping
    public ResponseEntity<Page<ReservationResponse>> listReservations(
        @RequestParam(name = "userId") @NotBlank String userId,
        Pageable pageable
    ) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required and cannot be blank");
        }
        return ResponseEntity.ok(reservationService.listByUser(userId, pageable));
    }

    /**
     * Confirm a reservation via the confirmation saga (external payment step + DB confirmation/compensation).
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(@PathVariable UUID id) {
        return ResponseEntity.ok(sagaCoordinator.executeConfirmationSaga(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancelReservation(@PathVariable UUID id) {
        return ResponseEntity.ok(reservationService.cancelReservation(id));
    }
}

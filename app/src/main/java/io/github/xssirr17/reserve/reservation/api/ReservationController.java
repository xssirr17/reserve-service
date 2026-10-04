package io.github.xssirr17.reserve.reservation.api;

import io.github.xssirr17.reserve.reservation.dto.CreateReservationRequest;
import io.github.xssirr17.reserve.reservation.dto.ReservationResponse;
import io.github.xssirr17.reserve.reservation.service.ReservationService;
import io.github.xssirr17.reserve.saga.ReservationSagaCoordinator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/reservations")
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
     * @return 201 Created with Location header and ReservationResponse
     */
    @PostMapping
    public ResponseEntity<ReservationResponse> createReservation(
        @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
        @RequestBody @Valid CreateReservationRequest request
    ) {
        ReservationResponse response = reservationService.createReservation(request, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(response.id())
            .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> getReservation(@PathVariable UUID id) {
        return ResponseEntity.ok(reservationService.getReservation(id));
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

package io.github.xssirr17.reserve.saga;

import io.github.xssirr17.reserve.reservation.dto.ReservationResponse;
import io.github.xssirr17.reserve.reservation.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ReservationSagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ReservationSagaCoordinator.class);

    private final ReservationService reservationService;
    private final PaymentGateway paymentGateway;

    public ReservationSagaCoordinator(ReservationService reservationService,
                                      PaymentGateway paymentGateway) {
        this.reservationService = reservationService;
        this.paymentGateway = paymentGateway;
    }

    /**
     * Orchestrates the confirmation saga:
     * 1. Inspects reservation.
     * 2. Calls non-transactional external payment step OUTSIDE of any DB transaction.
     * 3. On success: confirms reservation in a new DB transaction.
     * 4. On failure: compensates by cancelling reservation and releasing capacity in a new DB transaction.
     */
    public ReservationResponse executeConfirmationSaga(UUID reservationId) {
        ReservationResponse reservation = reservationService.getReservation(reservationId);
        log.info("[SAGA] Starting confirmation saga for reservation {}", reservationId);

        PaymentGateway.PaymentResult result;
        try {
            // External call outside any database transaction
            result = paymentGateway.processPayment(reservationId, reservation.quantity());
        } catch (Exception ex) {
            log.error("[SAGA-ERROR] External call threw exception, compensating: {}", ex.getMessage());
            reservationService.cancelReservation(reservationId);
            throw ex;
        }

        if (result.success()) {
            log.info("[SAGA] External step succeeded (txn={}), confirming reservation", result.transactionId());
            return reservationService.confirmReservation(reservationId);
        } else {
            log.warn("[SAGA] External step failed ({}), compensating by cancelling reservation", result.errorMessage());
            reservationService.cancelReservation(reservationId);
            return reservationService.getReservation(reservationId);
        }
    }
}

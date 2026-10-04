package io.github.xssirr17.reserve.saga;

import io.github.xssirr17.reserve.common.error.InvalidStateTransitionException;
import io.github.xssirr17.reserve.reservation.domain.ReservationStatus;
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
     * 1. Inspects reservation: if already CONFIRMED, return idempotent state (no charge);
     *    if CANCELLED/EXPIRED/COMPLETED, return proper conflict error.
     * 2. Calls non-transactional external payment step OUTSIDE of any DB transaction using
     *    idempotency key derived from reservationId to prevent double charges.
     * 3. On success: confirms reservation in a new DB transaction. If local confirm fails,
     *    safely compensates (cancels reservation and refunds payment).
     * 4. On failure: compensates by cancelling reservation and releasing capacity.
     */
    public ReservationResponse executeConfirmationSaga(UUID reservationId) {
        ReservationResponse reservation = reservationService.getReservation(reservationId);
        log.info("[SAGA] Starting confirmation saga for reservation {}", reservationId);

        if (reservation.status() == ReservationStatus.CONFIRMED) {
            log.info("[SAGA] Reservation {} is already CONFIRMED, returning without charge", reservationId);
            return reservation;
        }

        if (reservation.status() != ReservationStatus.PENDING) {
            throw new InvalidStateTransitionException(reservation.status(), ReservationStatus.CONFIRMED);
        }

        String paymentKey = "pay:reservation:" + reservationId;
        PaymentGateway.PaymentResult result;
        try {
            // External call outside any database transaction, with derived idempotency key
            result = paymentGateway.processPayment(reservationId, reservation.quantity(), paymentKey);
        } catch (Exception ex) {
            log.error("[SAGA-ERROR] External payment threw exception, compensating: {}", ex.getMessage());
            compensate(reservationId, paymentKey);
            throw ex;
        }

        if (result.success()) {
            log.info("[SAGA] External step succeeded (txn={}), confirming reservation", result.transactionId());
            try {
                return reservationService.confirmReservation(reservationId);
            } catch (Exception ex) {
                log.error("[SAGA-ERROR] Local confirm failed after successful payment, compensating: {}", ex.getMessage());
                compensate(reservationId, paymentKey);
                throw ex;
            }
        } else {
            log.warn("[SAGA] External step failed ({}), compensating by cancelling reservation", result.errorMessage());
            compensate(reservationId, paymentKey);
            return reservationService.getReservation(reservationId);
        }
    }

    private void compensate(UUID reservationId, String paymentKey) {
        try {
            reservationService.cancelReservation(reservationId);
        } catch (Exception cancelEx) {
            log.warn("[SAGA-WARN] Failed to cancel reservation during compensation: {}", cancelEx.getMessage());
        }
        try {
            paymentGateway.refundPayment(reservationId, paymentKey);
        } catch (Exception refundEx) {
            log.warn("[SAGA-WARN] Failed to refund payment during compensation: {}", refundEx.getMessage());
        }
    }
}

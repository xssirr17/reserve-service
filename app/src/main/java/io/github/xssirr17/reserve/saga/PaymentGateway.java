package io.github.xssirr17.reserve.saga;

import java.util.UUID;

public interface PaymentGateway {

    PaymentResult processPayment(UUID reservationId, int amount);

    record PaymentResult(boolean success, String transactionId, String errorMessage) {
        public static PaymentResult success(String transactionId) {
            return new PaymentResult(true, transactionId, null);
        }

        public static PaymentResult failure(String errorMessage) {
            return new PaymentResult(false, null, errorMessage);
        }
    }
}

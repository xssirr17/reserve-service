package io.github.xssirr17.reserve.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    @Override
    public PaymentResult processPayment(UUID reservationId, int amount) {
        log.info("[SAGA-EXTERNAL-STEP] Initiating payment for reservationId={}, amount={}", reservationId, amount);
        return PaymentResult.success("txn-" + UUID.randomUUID());
    }
}

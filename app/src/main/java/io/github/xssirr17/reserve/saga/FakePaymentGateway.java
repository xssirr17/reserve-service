package io.github.xssirr17.reserve.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class FakePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

    private final ConcurrentHashMap<String, PaymentResult> processedPayments = new ConcurrentHashMap<>();
    private final Set<String> refundedPayments = ConcurrentHashMap.newKeySet();
    private final AtomicInteger chargeCount = new AtomicInteger(0);
    private final AtomicInteger refundCount = new AtomicInteger(0);

    @Override
    public PaymentResult processPayment(UUID reservationId, int amount, String paymentIdempotencyKey) {
        log.info("[SAGA-EXTERNAL-STEP] Initiating payment for reservationId={}, amount={}, key={}",
            reservationId, amount, paymentIdempotencyKey);

        return processedPayments.computeIfAbsent(paymentIdempotencyKey, key -> {
            chargeCount.incrementAndGet();
            return PaymentResult.success("txn-" + UUID.randomUUID());
        });
    }

    @Override
    public PaymentResult refundPayment(UUID reservationId, String paymentIdempotencyKey) {
        log.info("[SAGA-EXTERNAL-STEP] Initiating refund for reservationId={}, key={}",
            reservationId, paymentIdempotencyKey);
        if (refundedPayments.add(paymentIdempotencyKey)) {
            refundCount.incrementAndGet();
        }
        return PaymentResult.success("refund-" + UUID.randomUUID());
    }

    public int getChargeCount() {
        return chargeCount.get();
    }

    public int getRefundCount() {
        return refundCount.get();
    }

    public void reset() {
        processedPayments.clear();
        refundedPayments.clear();
        chargeCount.set(0);
        refundCount.set(0);
    }
}

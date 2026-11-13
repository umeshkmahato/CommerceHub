package com.commercehub.payment.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated payment service provider.
 *
 * WHY simulated: we demonstrate the full payment state machine, ledger,
 * idempotency and saga integration WITHOUT touching real money or needing
 * PCI-DSS scope. In production this adapter is replaced by a real PSP
 * client (Stripe/Adyen/Braintree) behind the same PaymentGateway port.
 *
 * Behavior controls (env-configurable, for demos/tests):
 *  - payment.failure-rate: probability of decline (0.0–1.0)
 *  - payment.max-charge: amounts above this always decline
 *  - Deterministic refs look like real PSP tokens — NEVER card data.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private final double failureRate;
    private final BigDecimal maxCharge;

    public SimulatedPaymentGateway(
            @Value("${payment.failure-rate:0.0}") double failureRate,
            @Value("${payment.max-charge:100000.00}") BigDecimal maxCharge) {
        this.failureRate = failureRate;
        this.maxCharge = maxCharge;
    }

    @Override
    public ChargeResult charge(BigDecimal amount, String currency,
                               String idempotencyKey, String orderRef) {
        // Simulate PSP latency — real gateways take 200-800ms.
        simulateLatency();

        String ref = "gw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        if (amount.compareTo(maxCharge) > 0) {
            return new ChargeResult(false, ref, "AMOUNT_EXCEEDS_LIMIT");
        }
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            return new ChargeResult(false, ref, "CARD_DECLINED");
        }
        return new ChargeResult(true, ref, null);
    }

    @Override
    public RefundResult refund(BigDecimal amount, String gatewayRef, String idempotencyKey) {
        simulateLatency();
        String ref = "rf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        return new RefundResult(true, ref, null);
    }

    private void simulateLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(150, 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

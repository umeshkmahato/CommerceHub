package com.commercehub.payment.gateway;

import java.math.BigDecimal;

/**
 * Payment gateway port — hexagonal architecture in action.
 *
 * The domain (PaymentService) depends on THIS interface, not on any PSP.
 * Swapping the simulated gateway for Stripe/Adyen later = one new adapter,
 * zero changes to business logic. This is the Adapter pattern + DIP.
 */
public interface PaymentGateway {

    /** Result of a charge attempt. */
    record ChargeResult(boolean approved, String gatewayRef, String declineReason) {}

    /** Result of a refund attempt. */
    record RefundResult(boolean success, String gatewayRef, String reason) {}

    /**
     * Charge a customer. Implementations must be idempotent on their side
     * too — the idempotencyKey is passed through to the PSP.
     */
    ChargeResult charge(BigDecimal amount, String currency,
                        String idempotencyKey, String orderRef);

    /** Refund a previously captured charge (full or partial amount). */
    RefundResult refund(BigDecimal amount, String gatewayRef, String idempotencyKey);
}

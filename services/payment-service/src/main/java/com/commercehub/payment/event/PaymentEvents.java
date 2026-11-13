package com.commercehub.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka contracts for payment-service.
 *
 * Consumed: inventory.reserved (from inventory-service), order.cancelled
 *           (from order-service).
 * Published: payment.completed | payment.failed | payment.refunded —
 *            these drive order-service's saga transitions and
 *            notification-service emails.
 */
public final class PaymentEvents {
    private PaymentEvents() {}

    /** Consumed: inventory reserved → time to charge. */
    public record InventoryReservedEvent(
            UUID eventId,
            String eventType,
            Long orderId,
            BigDecimal amount,
            Long customerId,
            List<ReservationLine> reservations,
            Instant occurredAt,
            int version
    ) {
        public record ReservationLine(Long productId, String sku,
                                      int quantity, String reservationNo) {}
    }

    /** Consumed: order cancelled → auto-refund if captured. */
    public record OrderCancelledEvent(
            UUID eventId,
            String eventType,
            Long orderId,
            String reason,
            Instant occurredAt,
            int version
    ) {}

    /** Published: charge captured successfully. */
    public record PaymentCompletedEvent(
            UUID eventId,
            String eventType,               // "payment.completed"
            Long paymentId,
            String paymentNumber,
            Long orderId,
            Long customerId,
            BigDecimal amount,
            String currency,
            String gatewayRef,
            Instant occurredAt,
            int version
    ) {}

    /** Published: charge declined/failed. */
    public record PaymentFailedEvent(
            UUID eventId,
            String eventType,               // "payment.failed"
            Long paymentId,
            Long orderId,
            Long customerId,
            BigDecimal amount,
            String reason,
            Instant occurredAt,
            int version
    ) {}

    /** Published: refund issued (manual or auto on cancel). */
    public record PaymentRefundedEvent(
            UUID eventId,
            String eventType,               // "payment.refunded"
            Long paymentId,
            Long orderId,
            BigDecimal amount,
            String reason,
            Instant occurredAt,
            int version
    ) {}
}

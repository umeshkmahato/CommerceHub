package com.commercehub.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Order domain events — the saga's message contracts.
 * order.created drives inventory→payment; order.confirmed/cancelled/
 * failed drive notifications and cart cleanup.
 */
public final class OrderEvents {
    private OrderEvents() {}

    public record OrderCreatedEvent(
            UUID eventId, String eventType,          // "order.created"
            Long orderId, String orderNumber, Long customerId,
            BigDecimal totalAmount,
            List<OrderItemPayload> items,
            Instant occurredAt, int version
    ) {
        public record OrderItemPayload(Long productId, String sku, String name,
                                       int quantity, BigDecimal unitPrice) {}
    }

    public record OrderConfirmedEvent(
            UUID eventId, String eventType,          // "order.confirmed"
            Long orderId, String orderNumber, Long customerId,
            BigDecimal totalAmount, Instant occurredAt, int version
    ) {}

    public record OrderCancelledEvent(
            UUID eventId, String eventType,          // "order.cancelled"
            Long orderId, String orderNumber, Long customerId,
            String reason, Instant occurredAt, int version
    ) {}

    public record OrderFailedEvent(
            UUID eventId, String eventType,          // "order.failed"
            Long orderId, String orderNumber, Long customerId,
            String reason, Instant occurredAt, int version
    ) {}

    /** Consumed events (produced by other services). */
    public record InventoryFailedEvent(
            UUID eventId, String eventType,
            Long orderId,
            List<FailureLine> failures,
            Instant occurredAt, int version
    ) {
        public record FailureLine(Long productId, String sku, int requested,
                                  int available, String reason) {}
    }

    public record PaymentCompletedEvent(
            UUID eventId, String eventType,
            Long paymentId, String paymentNumber,
            Long orderId, Long customerId,
            BigDecimal amount, String currency, String gatewayRef,
            Instant occurredAt, int version
    ) {}

    public record PaymentFailedEvent(
            UUID eventId, String eventType,
            Long paymentId, Long orderId, Long customerId,
            BigDecimal amount, String reason,
            Instant occurredAt, int version
    ) {}
}

package com.commercehub.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka event contracts for inventory-service.
 *
 * CONTRACT NOTE: OrderCreated mirrors the order-service event payload
 * (docs/kafka-topics.md is the source of truth). In production these
 * shared contracts live in a published schema registry / shared library —
 * we duplicate the record here with a comment to make the coupling explicit.
 */
public final class InventoryEvents {
    private InventoryEvents() {}

    /** Consumed: order-service emits this when an order enters PENDING. */
    public record OrderCreatedEvent(
            UUID eventId,
            String eventType,               // "order.created"
            Long orderId,
            String orderNumber,
            Long customerId,
            BigDecimal totalAmount,         // authoritative order total for payment step
            List<OrderItem> items,
            Instant occurredAt,
            int version
    ) {
        public record OrderItem(Long productId, String sku, String name,
                                int quantity, BigDecimal unitPrice) {}
    }

    /** Consumed: order cancelled → release reservations. */
    public record OrderCancelledEvent(
            UUID eventId,
            String eventType,               // "order.cancelled"
            Long orderId,
            String reason,
            Instant occurredAt,
            int version
    ) {}

    /** Published (via outbox): all items reserved successfully. */
    public record InventoryReservedEvent(
            UUID eventId,
            String eventType,               // "inventory.reserved"
            Long orderId,
            BigDecimal amount,              // order total to charge
            Long customerId,
            List<ReservationLine> reservations,
            Instant occurredAt,
            int version
    ) {
        public record ReservationLine(Long productId, String sku,
                                      int quantity, String reservationNo) {}
    }

    /** Published (via outbox): one or more items could not be reserved. */
    public record InventoryFailedEvent(
            UUID eventId,
            String eventType,               // "inventory.failed"
            Long orderId,
            List<FailureLine> failures,
            Instant occurredAt,
            int version
    ) {
        public record FailureLine(Long productId, String sku,
                                  int requested, int available, String reason) {}
    }
}

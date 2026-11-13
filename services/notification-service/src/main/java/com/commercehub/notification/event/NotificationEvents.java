package com.commercehub.notification.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Notification-relevant event contracts (mirrors producers' payloads).
 * We only declare the fields we render — Jackson ignores the rest, which
 * is also how schema evolution stays backward-compatible.
 */
public final class NotificationEvents {
    private NotificationEvents() {}

    public record OrderConfirmedEvent(
            UUID eventId, String eventType,
            Long orderId, String orderNumber, Long customerId,
            BigDecimal totalAmount, Instant occurredAt, int version
    ) {}

    public record OrderCancelledEvent(
            UUID eventId, String eventType,
            Long orderId, String orderNumber, Long customerId,
            String reason, Instant occurredAt, int version
    ) {}

    public record OrderFailedEvent(
            UUID eventId, String eventType,
            Long orderId, String orderNumber, Long customerId,
            String reason, Instant occurredAt, int version
    ) {}

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

    public record PaymentRefundedEvent(
            UUID eventId, String eventType,
            Long paymentId, Long orderId,
            BigDecimal amount, String reason,
            Instant occurredAt, int version
    ) {}
}

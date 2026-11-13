package com.commercehub.notification.event;

import com.commercehub.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Notification consumer — one listener per domain event.
 *
 * Each handler: dedupe via Redis SETNX → render → "send". Failures retry
 * per the container factory's error handler; after max attempts the
 * DeadLetterPublishingRecoverer routes to <topic>.DLT for inspection —
 * notifications are never silently dropped.
 */
@Component
public class NotificationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private final NotificationService notifier;
    private final ObjectMapper objectMapper;

    public NotificationEventConsumer(NotificationService notifier,
                                     ObjectMapper objectMapper) {
        this.notifier = notifier;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order-confirmed", groupId = "notification-service",
                   containerFactory = "notificationKafkaListenerFactory")
    public void onOrderConfirmed(String payload) throws Exception {
        var e = objectMapper.readValue(payload, NotificationEvents.OrderConfirmedEvent.class);
        if (!notifier.markIfNew(e.eventId().toString(), "email")) return;
        notifier.sendEmail("customer-" + e.customerId() + "@example.com",
                "Order confirmed: " + e.orderNumber(),
                "Your order " + e.orderNumber() + " is confirmed. " +
                "Total: " + e.totalAmount());
    }

    @KafkaListener(topics = "order-cancelled", groupId = "notification-service",
                   containerFactory = "notificationKafkaListenerFactory")
    public void onOrderCancelled(String payload) throws Exception {
        var e = objectMapper.readValue(payload, NotificationEvents.OrderCancelledEvent.class);
        if (!notifier.markIfNew(e.eventId().toString(), "email")) return;
        notifier.sendEmail("customer-" + e.customerId() + "@example.com",
                "Order cancelled: " + e.orderNumber(),
                "Your order " + e.orderNumber() + " was cancelled. Reason: " + e.reason());
    }

    @KafkaListener(topics = "order-failed", groupId = "notification-service",
                   containerFactory = "notificationKafkaListenerFactory")
    public void onOrderFailed(String payload) throws Exception {
        var e = objectMapper.readValue(payload, NotificationEvents.OrderFailedEvent.class);
        if (!notifier.markIfNew(e.eventId().toString(), "email")) return;
        notifier.sendEmail("customer-" + e.customerId() + "@example.com",
                "Order failed: " + e.orderNumber(),
                "We couldn't complete order " + e.orderNumber() + ". Reason: " + e.reason());
    }

    @KafkaListener(topics = "payment-completed", groupId = "notification-service",
                   containerFactory = "notificationKafkaListenerFactory")
    public void onPaymentCompleted(String payload) throws Exception {
        var e = objectMapper.readValue(payload, NotificationEvents.PaymentCompletedEvent.class);
        if (!notifier.markIfNew(e.eventId().toString(), "email")) return;
        notifier.sendEmail("customer-" + e.customerId() + "@example.com",
                "Payment received: " + e.paymentNumber(),
                "Payment of " + e.amount() + " " + e.currency() +
                " for order " + e.orderId() + " was captured.");
        notifier.sendSms("customer-" + e.customerId(),
                "ShopSphere: payment of " + e.amount() + " received. Thank you!");
    }

    @KafkaListener(topics = "payment-failed", groupId = "notification-service",
                   containerFactory = "notificationKafkaListenerFactory")
    public void onPaymentFailed(String payload) throws Exception {
        var e = objectMapper.readValue(payload, NotificationEvents.PaymentFailedEvent.class);
        if (!notifier.markIfNew(e.eventId().toString(), "email")) return;
        notifier.sendEmail("customer-" + e.customerId() + "@example.com",
                "Payment failed for order " + e.orderId(),
                "Reason: " + e.reason() + ". Please update your payment method.");
    }

    @KafkaListener(topics = "payment-refunded", groupId = "notification-service",
                   containerFactory = "notificationKafkaListenerFactory")
    public void onPaymentRefunded(String payload) throws Exception {
        var e = objectMapper.readValue(payload, NotificationEvents.PaymentRefundedEvent.class);
        if (!notifier.markIfNew(e.eventId().toString(), "email")) return;
        notifier.sendEmail("customer-order-" + e.orderId() + "@example.com",
                "Refund issued: " + e.amount(),
                "A refund of " + e.amount() + " was issued. Reason: " + e.reason());
    }
}

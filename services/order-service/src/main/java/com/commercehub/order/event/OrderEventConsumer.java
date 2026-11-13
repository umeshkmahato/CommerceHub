package com.commercehub.order.event;

import com.commercehub.order.entity.ProcessedEvent;
import com.commercehub.order.repository.ProcessedEventRepository;
import com.commercehub.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga consumer — reacts to inventory/payment outcomes.
 * Group "order-service"; dedupe + effect in one transaction (same
 * at-least-once contract as the other services).
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final OrderService orderService;
    private final ProcessedEventRepository processedRepo;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(OrderService orderService,
                              ProcessedEventRepository processedRepo,
                              ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.processedRepo = processedRepo;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "inventory-failed", groupId = "order-service",
                   containerFactory = "orderKafkaListenerFactory")
    @Transactional
    public void onInventoryFailed(String payload) throws Exception {
        OrderEvents.InventoryFailedEvent event =
                objectMapper.readValue(payload, OrderEvents.InventoryFailedEvent.class);
        String id = event.eventId().toString();
        if (processedRepo.existsById(id)) return;
        orderService.handleInventoryFailed(event);
        processedRepo.save(new ProcessedEvent(id, "inventory.failed"));
        log.info("inventory.failed processed for order {}", event.orderId());
    }

    @KafkaListener(topics = "payment-completed", groupId = "order-service",
                   containerFactory = "orderKafkaListenerFactory")
    @Transactional
    public void onPaymentCompleted(String payload) throws Exception {
        OrderEvents.PaymentCompletedEvent event =
                objectMapper.readValue(payload, OrderEvents.PaymentCompletedEvent.class);
        String id = event.eventId().toString();
        if (processedRepo.existsById(id)) return;
        orderService.handlePaymentCompleted(event);
        processedRepo.save(new ProcessedEvent(id, "payment.completed"));
        log.info("payment.completed processed for order {}", event.orderId());
    }

    @KafkaListener(topics = "payment-failed", groupId = "order-service",
                   containerFactory = "orderKafkaListenerFactory")
    @Transactional
    public void onPaymentFailed(String payload) throws Exception {
        OrderEvents.PaymentFailedEvent event =
                objectMapper.readValue(payload, OrderEvents.PaymentFailedEvent.class);
        String id = event.eventId().toString();
        if (processedRepo.existsById(id)) return;
        orderService.handlePaymentFailed(event);
        processedRepo.save(new ProcessedEvent(id, "payment.failed"));
        log.info("payment.failed processed for order {}", event.orderId());
    }
}

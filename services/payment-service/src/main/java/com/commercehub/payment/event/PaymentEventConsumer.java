package com.commercehub.payment.event;

import com.commercehub.payment.entity.ProcessedEvent;
import com.commercehub.payment.repository.ProcessedEventRepository;
import com.commercehub.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka consumer — saga events that trigger charges and refunds.
 * Same idempotency contract as inventory-service: processed_events dedupe
 * inside the same transaction as the business effect.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentService paymentService;
    private final ProcessedEventRepository processedRepo;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(PaymentService paymentService,
                                ProcessedEventRepository processedRepo,
                                ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.processedRepo = processedRepo;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "inventory-reserved", groupId = "payment-service",
                   containerFactory = "paymentKafkaListenerFactory")
    @Transactional
    public void onInventoryReserved(String payload) throws Exception {
        PaymentEvents.InventoryReservedEvent event =
                objectMapper.readValue(payload, PaymentEvents.InventoryReservedEvent.class);
        String eventId = event.eventId().toString();

        if (processedRepo.existsById(eventId)) {
            log.info("Duplicate inventory.reserved ignored: {}", eventId);
            return;
        }
        paymentService.chargeForOrder(event);
        processedRepo.save(new ProcessedEvent(eventId, "inventory.reserved"));
    }

    @KafkaListener(topics = "order-cancelled", groupId = "payment-service",
                   containerFactory = "paymentKafkaListenerFactory")
    @Transactional
    public void onOrderCancelled(String payload) throws Exception {
        PaymentEvents.OrderCancelledEvent event =
                objectMapper.readValue(payload, PaymentEvents.OrderCancelledEvent.class);
        String eventId = event.eventId().toString();

        if (processedRepo.existsById(eventId)) {
            log.info("Duplicate order.cancelled ignored: {}", eventId);
            return;
        }
        paymentService.handleOrderCancelled(event);
        processedRepo.save(new ProcessedEvent(eventId, "order.cancelled"));
    }
}

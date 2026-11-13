package com.commercehub.inventory.event;

import com.commercehub.inventory.entity.ProcessedEvent;
import com.commercehub.inventory.repository.ProcessedEventRepository;
import com.commercehub.inventory.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka consumer — order lifecycle events that drive stock mutations.
 *
 * Listeners take the raw String payload and deserialize explicitly —
 * keeps the wire contract (JSON) independent of Java type headers.
 *
 * Idempotency (defense in depth):
 *  1. processed_events.event_id PK — duplicate delivery short-circuits.
 *  2. (order_id, product_id, ACTIVE) unique constraint on reservations.
 *  3. Row-locked stock mutations — even a missed dedupe can't oversell.
 *
 * Dedupe + business logic share ONE transaction: if processing throws,
 * the dedupe row rolls back and Kafka redelivers — we never mark an
 * event processed that didn't actually apply.
 */
@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final StockService stockService;
    private final ProcessedEventRepository processedRepo;
    private final ObjectMapper objectMapper;

    public InventoryEventConsumer(StockService stockService,
                                  ProcessedEventRepository processedRepo,
                                  ObjectMapper objectMapper) {
        this.stockService = stockService;
        this.processedRepo = processedRepo;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order-created", groupId = "inventory-service",
                   containerFactory = "inventoryKafkaListenerFactory")
    @Transactional
    public void onOrderCreated(String payload) throws Exception {
        InventoryEvents.OrderCreatedEvent event =
                objectMapper.readValue(payload, InventoryEvents.OrderCreatedEvent.class);
        String eventId = event.eventId().toString();

        if (processedRepo.existsById(eventId)) {
            log.info("Duplicate order.created ignored: {}", eventId);
            return;
        }
        stockService.handleOrderCreated(event);
        processedRepo.save(new ProcessedEvent(eventId, "order.created"));
        log.info("Processed order.created {} for order {}", eventId, event.orderId());
    }

    @KafkaListener(topics = "order-cancelled", groupId = "inventory-service",
                   containerFactory = "inventoryKafkaListenerFactory")
    @Transactional
    public void onOrderCancelled(String payload) throws Exception {
        InventoryEvents.OrderCancelledEvent event =
                objectMapper.readValue(payload, InventoryEvents.OrderCancelledEvent.class);
        String eventId = event.eventId().toString();

        if (processedRepo.existsById(eventId)) {
            log.info("Duplicate order.cancelled ignored: {}", eventId);
            return;
        }
        stockService.releaseForOrder(event.orderId(),
                event.reason() != null ? event.reason() : "ORDER_CANCELLED");
        processedRepo.save(new ProcessedEvent(eventId, "order.cancelled"));
        log.info("Processed order.cancelled {} for order {}", eventId, event.orderId());
    }
}

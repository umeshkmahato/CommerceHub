package com.commercehub.product.event;

import com.commercehub.product.event.ProductEvents.ProductEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Publishes product domain events to Kafka.
 *
 * @Async: sending is off the request thread — a slow/unavailable broker
 * must never add latency to a product save or, worse, fail the request
 * after the DB commit (that inconsistency is what the outbox pattern solves
 * in order/payment services; product events are informational, so async
 * fire-and-forget with logging is acceptable here — a documented trade-off).
 */
@Component
public class ProductEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ProductEventPublisher.class);

    private final KafkaTemplate<String, ProductEvent> kafkaTemplate;
    private final String topic;

    public ProductEventPublisher(KafkaTemplate<String, ProductEvent> kafkaTemplate,
                                 @Value("${kafka.topics.product-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Async
    public void publish(ProductEvent event) {
        try {
            // Key = productId → all events for one product land on the same
            // partition, preserving per-product ordering.
            kafkaTemplate.send(topic, String.valueOf(event.productId()), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} for product {}: {}",
                                    event.eventType(), event.productId(), ex.getMessage());
                        } else {
                            log.info("Published {} productId={} offset={}",
                                    event.eventType(), event.productId(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("Kafka send threw for {}: {}", event.eventType(), e.getMessage());
        }
    }
}

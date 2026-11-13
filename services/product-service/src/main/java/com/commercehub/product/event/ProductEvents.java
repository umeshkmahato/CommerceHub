package com.commercehub.product.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain event contracts for product-service.
 *
 * Rules every event in ShopSphere follows (see docs/kafka-topics.md):
 *  - eventId: unique UUID for consumer-side idempotency
 *  - occurredAt: when the fact happened (producer clock)
 *  - version: schema version for safe evolution
 *  - immutable record — events are facts, never mutated
 */
public final class ProductEvents {
    private ProductEvents() {}

    public record ProductEvent(
            UUID eventId,
            String eventType,        // product.created | product.updated | product.deactivated
            Long productId,
            String sku,
            String name,
            BigDecimal price,
            String categorySlug,
            Instant occurredAt,
            int version
    ) {
        public static ProductEvent of(String type, Long id, String sku, String name,
                                      BigDecimal price, String categorySlug) {
            return new ProductEvent(UUID.randomUUID(), type, id, sku, name,
                    price, categorySlug, Instant.now(), 1);
        }
    }
}

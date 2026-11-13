package com.commercehub.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Inventory Service — stock levels, reservations, overselling prevention.
 *
 * The most concurrency-critical service in the platform: a product's stock
 * row is a single-writer resource under flash-sale load. We use PESSIMISTIC
 * locking here (SELECT ... FOR UPDATE) because the check-and-decrement must
 * be atomic — optimistic retry would still allow oversell races at the DB
 * boundary under READ_COMMITTED.
 *
 * Consumes order.created / order.cancelled from Kafka; publishes
 * inventory.reserved / inventory.failed via the transactional outbox.
 */
@SpringBootApplication
@EnableScheduling   // outbox relay + reservation-expiry sweeper
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

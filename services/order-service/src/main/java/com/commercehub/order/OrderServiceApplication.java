package com.commercehub.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order Service — owns the checkout saga.
 *
 * Creates orders from the customer's cart, publishes order.created via
 * the transactional outbox, then reacts to inventory/payment events to
 * drive the state machine: PENDING → CONFIRMED | FAILED | CANCELLED.
 * This is choreography — no service calls another synchronously during
 * checkout; Kafka events carry the workflow forward.
 */
@SpringBootApplication
@EnableScheduling   // outbox relay
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

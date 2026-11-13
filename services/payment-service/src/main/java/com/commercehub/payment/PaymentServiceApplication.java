package com.commercehub.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment Service — simulated payment gateway, refunds, transaction ledger.
 *
 * Saga role: consumes inventory.reserved → charges the order → publishes
 * payment.completed / payment.failed via outbox. Also auto-refunds on
 * order.cancelled. NEVER stores raw card data — only gateway references.
 */
@SpringBootApplication
@EnableScheduling   // outbox relay
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}

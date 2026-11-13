package com.commercehub.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification Service — the platform's event consumer edge.
 *
 * NO database by design: it reacts to domain events and "sends" simulated
 * emails/SMS. State that matters (dedupe) lives in Redis with TTL —
 * notifications are ephemeral side-effects, not source-of-truth data.
 * This is the canonical example of an event-driven, DB-less service.
 */
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

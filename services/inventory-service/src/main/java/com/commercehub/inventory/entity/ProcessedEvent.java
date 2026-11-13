package com.commercehub.inventory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * ProcessedEvent — idempotent-consumer bookkeeping.
 *
 * Kafka gives AT-LEAST-ONCE delivery: the same event WILL arrive twice
 * eventually (rebalance, retry, producer retry). Recording event_id before
 * applying effects makes consumers effectively exactly-once.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt = LocalDateTime.now();

    public ProcessedEvent() {}
    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }

    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public LocalDateTime getProcessedAt() { return processedAt; }
}

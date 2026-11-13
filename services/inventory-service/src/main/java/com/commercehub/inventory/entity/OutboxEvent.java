package com.commercehub.inventory.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * OutboxEvent — written in the SAME transaction as the state change,
 * published to Kafka after commit by OutboxRelay.
 *
 * This closes the dual-write gap: reservation row + event row commit
 * atomically; the relay guarantees the event reaches the broker. If the
 * broker is down, events queue in the table and publish on recovery —
 * the saga never silently stalls.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "aggregate_type", nullable = false, length = 60)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt = LocalDateTime.now();

    public static OutboxEvent of(String aggType, Long aggId, String eventType,
                                 Map<String, Object> payload) {
        OutboxEvent e = new OutboxEvent();
        e.id = UUID.randomUUID().toString();
        e.aggregateType = aggType;
        e.aggregateId = aggId;
        e.eventType = eventType;
        e.payload = payload;
        return e;
    }

    public String getId() { return id; }
    public String getEventType() { return eventType; }
    public Long getAggregateId() { return aggregateId; }
    public Map<String, Object> getPayload() { return payload; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void markPublished() { publishedAt = LocalDateTime.now(); }
    public void recordFailure(String error) {
        attempts++;
        lastError = error;
        // Exponential backoff for the relay's next pick-up: 5s * 2^attempts, cap 5min.
        availableAt = LocalDateTime.now()
                .plusSeconds(Math.min(300, 5L * (1L << Math.min(attempts, 6))));
    }
}

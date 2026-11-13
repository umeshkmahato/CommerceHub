package com.commercehub.payment.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox (PostgreSQL variant — native UUID id type).
 * payment.completed / payment.failed / payment.refunded MUST reach the
 * broker exactly when the payment row commits — the saga depends on it.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 60)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt = LocalDateTime.now();

    public static OutboxEvent of(String aggType, Long aggId, String eventType,
                                 Map<String, Object> payload) {
        OutboxEvent e = new OutboxEvent();
        e.id = UUID.randomUUID();
        e.aggregateType = aggType;
        e.aggregateId = aggId;
        e.eventType = eventType;
        e.payload = payload;
        return e;
    }

    public String getEventType() { return eventType; }
    public UUID getId() { return id; }
    public Long getAggregateId() { return aggregateId; }
    public Map<String, Object> getPayload() { return payload; }
    public void markPublished() { publishedAt = LocalDateTime.now(); }
    public void recordFailure(String error) {
        attempts++;
        lastError = error;
        availableAt = LocalDateTime.now()
                .plusSeconds(Math.min(300, 5L * (1L << Math.min(attempts, 6))));
    }
}

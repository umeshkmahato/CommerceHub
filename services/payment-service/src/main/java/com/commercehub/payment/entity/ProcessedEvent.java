package com.commercehub.payment.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Idempotent-consumer bookkeeping — same pattern as inventory-service. */
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
}

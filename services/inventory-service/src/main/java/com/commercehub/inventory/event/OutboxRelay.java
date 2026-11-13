package com.commercehub.inventory.event;

import com.commercehub.inventory.entity.OutboxEvent;
import com.commercehub.inventory.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Outbox relay — bridges the transactional-outbox table to Kafka.
 *
 * How it works:
 *  1. Every 2s, poll unpublished events (PESSIMISTIC lock → safe with
 *     multiple relay instances during rolling deploys).
 *  2. Publish each to its event_type topic; on success mark published_at.
 *  3. On failure record attempts + exponential backoff via available_at —
 *     the event retries until it succeeds or ops intervenes.
 *
 * At-least-once: consumers MUST be idempotent (they are — processed_events).
 * This is the standard outbox trade-off: we choose duplicates-over-loss.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OutboxEventRepository outboxRepo,
                       KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:2000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> pending =
                outboxRepo.findUnpublishedForUpdate(LocalDateTime.now());
        for (OutboxEvent event : pending) {
            try {
                String payload = objectMapper.writeValueAsString(event.getPayload());
                // Topic = event_type ("inventory.reserved") — dots become
                // dashes in topic names; keep consistent with docs.
                kafkaTemplate.send(event.getEventType().replace('.', '-'),
                        String.valueOf(event.getAggregateId()), payload)
                        .get();   // block briefly for broker ack
                event.markPublished();
                outboxRepo.save(event);
            } catch (Exception e) {
                event.recordFailure(e.getMessage());
                outboxRepo.save(event);
                log.error("Outbox publish failed for {}: {}", event.getId(), e.getMessage());
            }
        }
    }
}

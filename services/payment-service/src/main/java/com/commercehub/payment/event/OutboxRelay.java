package com.commercehub.payment.event;

import com.commercehub.payment.entity.OutboxEvent;
import com.commercehub.payment.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox relay — identical contract to inventory-service's: poll
 * unpublished events under pessimistic lock, publish, mark, back off on
 * failure. Kept per-service (not shared lib) so each team owns its relay.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxRepo;
    private final KafkaTemplate<String, String> outboxKafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OutboxEventRepository outboxRepo,
                       KafkaTemplate<String, String> outboxKafkaTemplate,
                       ObjectMapper objectMapper) {
        this.outboxRepo = outboxRepo;
        this.outboxKafkaTemplate = outboxKafkaTemplate;
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
                outboxKafkaTemplate.send(event.getEventType().replace('.', '-'),
                        String.valueOf(event.getAggregateId()), payload).get();
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

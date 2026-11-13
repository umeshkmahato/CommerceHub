package com.commercehub.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Notification dispatcher — renders and "sends" simulated emails/SMS.
 *
 * Idempotency without a database: Redis SETNX on eventId. First delivery
 * wins; duplicates are dropped. TTL of 7 days covers Kafka retention
 * windows — after that a replayed ancient event would re-notify, which is
 * acceptable (and arguably correct) behavior.
 *
 * "Sending" is a structured log line — swap the send() body for SES/SendGrid
 * later without touching consumers.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String DEDUPE_PREFIX = "notif:seen:";
    private static final Duration DEDUPE_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public NotificationService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true if this is a NEW event (processed), false if duplicate.
     */
    public boolean markIfNew(String eventId, String channel) {
        Boolean isNew = redis.opsForValue()
                .setIfAbsent(DEDUPE_PREFIX + eventId, channel, DEDUPE_TTL);
        return Boolean.TRUE.equals(isNew);
    }

    /** Simulated email send — structured log is the "outbox" of record here. */
    public void sendEmail(String to, String subject, String body) {
        log.info("[EMAIL] to={} subject=\"{}\" body=\"{}\"", to, subject,
                body.length() > 200 ? body.substring(0, 200) + "…" : body);
    }

    /** Simulated SMS send. */
    public void sendSms(String to, String body) {
        log.info("[SMS] to={} body=\"{}\"", to, body);
    }
}

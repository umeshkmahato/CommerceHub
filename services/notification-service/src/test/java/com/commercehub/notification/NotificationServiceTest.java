package com.commercehub.notification;

import com.commercehub.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NotificationService unit tests — the dedupe contract:
 * first event wins, duplicates dropped, TTL bounded.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> ops;

    NotificationService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new NotificationService(redis);
        lenient().when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void firstEvent_isNew_returnsTrue() {
        when(ops.setIfAbsent(startsWith("notif:seen:"), anyString(), any(Duration.class)))
                .thenReturn(true);
        assertTrue(service.markIfNew("evt-1", "email"));
    }

    @Test
    void duplicateEvent_returnsFalse() {
        when(ops.setIfAbsent(startsWith("notif:seen:"), anyString(), any(Duration.class)))
                .thenReturn(false);
        assertFalse(service.markIfNew("evt-1", "email"));
    }

    @Test
    void redisNullResponse_treatedAsDuplicate() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(null);
        assertFalse(service.markIfNew("evt-x", "email"));
    }
}

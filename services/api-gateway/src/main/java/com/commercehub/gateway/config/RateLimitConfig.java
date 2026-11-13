package com.commercehub.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Rate limiting — Redis-backed so counters are shared across gateway
 * replicas (in-memory limiters would multiply the effective limit by pod
 * count — a classic scaling bug).
 *
 * Key resolver: client IP. For authenticated traffic we'd prefer userId,
 * but the filter runs before auth — IP is the pragmatic edge key.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        // 100 requests per 60s per key, burst allowed = replenish rate.
        return new RedisRateLimiter(100, 60, 100);
    }

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.justOrEmpty(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown");
    }
}

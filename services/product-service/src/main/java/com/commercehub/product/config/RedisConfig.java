package com.commercehub.product.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis wiring for the cache abstraction.
 *
 * Key decisions:
 *  - StringRedisSerializer for keys → human-readable, predictable keys
 *    like products::detail:42 (great for debugging with redis-cli).
 *  - GenericJackson2JsonRedisSerializer for values → stores JSON so cached
 *    objects survive restarts and are language-agnostic.
 *  - TTLs are set per-cache via @Cacheable#ttl or CacheManager config —
 *    see application.yml spring.cache.redis.time-to-live.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(cf);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer(om);
        template.setValueSerializer(json);
        template.setHashValueSerializer(json);
        template.afterPropertiesSet();
        return template;
    }
}

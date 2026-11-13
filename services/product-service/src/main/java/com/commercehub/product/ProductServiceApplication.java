package com.commercehub.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Product Service — catalog, categories, search, pricing.
 *
 * Owns productdb (MySQL). This is a READ-HEAVY service: storefront browsing
 * generates far more reads than writes, which is why Redis caching and
 * JOIN FETCH / EntityGraph query strategies matter here.
 *
 * @EnableCaching turns on Spring's cache abstraction; @Cacheable/@CacheEvict
 * on the service layer delegate to Redis — no cache code leaks into
 * controllers or repositories.
 *
 * @EnableAsync allows the Kafka producer to send events off the request
 * thread so a slow broker never blocks a storefront API call.
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
public class ProductServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}

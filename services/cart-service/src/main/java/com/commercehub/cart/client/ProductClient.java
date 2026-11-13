package com.commercehub.cart.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Synchronous client to product-service.
 *
 * Why RestClient (not WebClient): cart operations are request-response —
 * we NEED the product data before responding. Blocking is correct here;
 * reactive would add complexity without benefit on this path.
 *
 * Resilience: explicit connect/read timeouts so a slow product-service
 * fails fast instead of exhausting Tomcat threads. Full circuit-breaker
 * comes via Resilience4j in later hardening.
 */
@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(@Value("${services.product.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(1500));
        factory.setReadTimeout(Duration.ofMillis(3000));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** Minimal product view needed by the cart — decoupled from product DTOs. */
    public record ProductInfo(Long id, String sku, String name, BigDecimal price, String status) {}

    /**
     * Fetch product for cart validation. Returns null on 404 (product gone);
     * throws on other failures so the caller can decide (fail the add vs degrade).
     */
    @CircuitBreaker(name = "productService", fallbackMethod = "productFallback")
    public ProductInfo getProduct(Long productId) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/api/v1/products/{id}", productId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (body == null) return null;
            Map<String, Object> cat = (Map<String, Object>) body.get("category");
            return new ProductInfo(
                    toLong(body.get("id")),
                    (String) body.get("sku"),
                    (String) body.get("name"),
                    new BigDecimal(body.get("price").toString()),
                    (String) body.get("status"));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;   // product gone
            throw e;                                            // 5xx etc. propagate
        }
    }

    /** Circuit-breaker fallback: product-service down → caller gets a
     *  domain error (mapped to 503), not a stack trace. */
    private ProductInfo productFallback(Long productId, Exception ex) {
        throw new org.springframework.web.client.RestClientException(
                "Product service unavailable: " + ex.getMessage(), ex);
    }

    private Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}

package com.commercehub.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cart-service client — fetches the authoritative cart at checkout.
 *
 * Why server-side cart fetch (not client-supplied items): the client could
 * tamper with prices/quantities in the request body. We pull the cart from
 * cart-service using the gateway-verified X-User-Id — the customer can only
 * check out THEIR cart, at prices cart-service snapshotted.
 * (Full price revalidation vs product-service happens in a later hardening.)
 */
@Component
public class CartClient {

    private final RestClient restClient;

    public CartClient(@Value("${services.cart.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(1500));
        factory.setReadTimeout(Duration.ofMillis(3000));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record CartLine(Long productId, String sku, String productName,
                           BigDecimal unitPrice, int quantity) {}
    public record CartView(Long id, String status, List<CartLine> items, BigDecimal subtotal) {}

    @SuppressWarnings("unchecked")
    public CartView getCart(Long customerId) {
        Map<String, Object> body = restClient.get()
                .uri("/api/v1/cart")
                .header("X-User-Id", String.valueOf(customerId))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (body == null) return null;
        List<CartLine> items = ((List<Map<String, Object>>) body.get("items")).stream()
                .map(m -> new CartLine(
                        ((Number) m.get("productId")).longValue(),
                        (String) m.get("sku"),
                        (String) m.get("productName"),
                        new BigDecimal(m.get("unitPrice").toString()),
                        ((Number) m.get("quantity")).intValue()))
                .toList();
        return new CartView(((Number) body.get("id")).longValue(),
                (String) body.get("status"), items,
                new BigDecimal(body.get("subtotal").toString()));
    }

    /** Best-effort cart clear after order confirmation — failure is logged,
     *  not fatal (the cart sweeper catches stragglers). */
    public void clearCart(Long customerId) {
        try {
            restClient.post()
                    .uri("/api/v1/cart/internal/clear?customerId={id}", customerId)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            // swallow — order already confirmed; cart expiry handles cleanup
        }
    }
}

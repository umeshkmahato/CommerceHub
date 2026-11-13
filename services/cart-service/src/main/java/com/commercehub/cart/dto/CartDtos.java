package com.commercehub.cart.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cart API contracts. customerId is NEVER in these DTOs — it comes from the
 * gateway-injected X-User-Id header (same anti-IDOR rule as user-service).
 */
public final class CartDtos {
    private CartDtos() {}

    public record AddItemRequest(
            @NotNull Long productId,
            @Min(1) @Max(999) int quantity
    ) {}

    public record UpdateQuantityRequest(
            @Min(1) @Max(999) int quantity
    ) {}

    public record CartItemResponse(
            Long id,
            Long productId,
            String sku,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal
    ) {}

    public record CartResponse(
            Long id,
            String status,
            List<CartItemResponse> items,
            BigDecimal subtotal,
            int itemCount
    ) {}
}

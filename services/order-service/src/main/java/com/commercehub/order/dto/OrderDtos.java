package com.commercehub.order.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class OrderDtos {
    private OrderDtos() {}

    /** Checkout request — items come from the customer's cart server-side. */
    public record CreateOrderRequest(
            @NotBlank(message = "Shipping address required")
            @Size(max = 2000) String shippingAddressJson,
            Long addressId                        // optional: pick saved address
    ) {}

    public record OrderItemResponse(
            Long productId, String sku, String productName,
            int quantity, BigDecimal unitPrice, BigDecimal lineTotal
    ) {}

    public record OrderResponse(
            Long id, String orderNumber, String status, String sagaState,
            BigDecimal subtotal, BigDecimal taxAmount, BigDecimal shippingAmount,
            BigDecimal totalAmount, String currency,
            Map<String, Object> shippingAddress,
            List<OrderItemResponse> items,
            LocalDateTime createdAt, LocalDateTime updatedAt,
            LocalDateTime cancelledAt, String cancelReason
    ) {}

    public record CancelRequest(@Size(max = 300) String reason) {}

    public record StatusUpdateRequest(@NotBlank String status) {}
}

package com.commercehub.payment.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PaymentDtos {
    private PaymentDtos() {}

    /** Manual payment initiation (non-saga path, e.g. retry after failure). */
    public record InitiatePaymentRequest(
            @NotNull Long orderId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @Size(max = 80) String idempotencyKey   // client-supplied dedupe
    ) {}

    public record PaymentResponse(
            Long id,
            String paymentNumber,
            Long orderId,
            Long customerId,
            BigDecimal amount,
            String currency,
            String status,
            String gatewayRef,
            String failureReason,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {}

    public record TransactionResponse(
            Long id, String txnType, BigDecimal amount, String status,
            String gatewayRef, LocalDateTime createdAt
    ) {}
}

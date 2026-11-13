package com.commercehub.payment.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment aggregate root.
 *
 * idempotencyKey UNIQUE is the exactly-once charge guarantee: the saga
 * derives it deterministically ("saga-" + orderId) and the REST API accepts
 * a client-supplied key. Any retry/replay hits the constraint and returns
 * the original payment — a customer is never charged twice.
 */
@Entity
@Table(name = "payments")
public class Payment {

    public enum Status { INITIATED, AUTHORIZED, CAPTURED, FAILED, REFUNDED, PARTIALLY_REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_number", nullable = false, unique = true, length = 30)
    private String paymentNumber;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.INITIATED;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 80)
    private String idempotencyKey;

    /** Simulated PSP reference — NEVER a PAN/card number. */
    @Column(name = "gateway_ref", length = 80)
    private String gatewayRef;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public boolean isCaptured() { return status == Status.CAPTURED; }
    public boolean isRefundable() { return status == Status.CAPTURED || status == Status.PARTIALLY_REFUNDED; }

    // ---- getters/setters ----
    public Long getId() { return id; }
    public String getPaymentNumber() { return paymentNumber; }
    public void setPaymentNumber(String v) { paymentNumber = v; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long v) { orderId = v; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long v) { customerId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { currency = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public String getGatewayRef() { return gatewayRef; }
    public void setGatewayRef(String v) { gatewayRef = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { failureReason = v; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime v) { completedAt = v; }
}

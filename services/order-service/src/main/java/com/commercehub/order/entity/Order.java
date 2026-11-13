package com.commercehub.order.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Order aggregate root — the central entity of the platform.
 *
 * State machine (enforced in OrderService, documented in docs):
 *   PENDING ──► CONFIRMED ──► SHIPPED ──► DELIVERED
 *      │           │
 *      └──► CANCELLED / FAILED (compensation paths via saga events)
 *
 * sagaState records WHERE in the choreography the order currently is —
 * invaluable when debugging a stuck order ("stuck at PAYMENT_PENDING").
 */
@Entity
@Table(name = "orders")
public class Order {

    public enum Status { PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 30)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "shipping_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal shippingAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "shipping_address", nullable = false, columnDefinition = "jsonb")
    private String shippingAddress;

    @Column(name = "saga_state", nullable = false, length = 30)
    private String sagaState = "INVENTORY_RESERVED_PENDING";

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    // ---- state transitions (business rules live here) ----

    public void confirm() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Cannot confirm order in status " + status);
        }
        status = Status.CONFIRMED;
        sagaState = "PAYMENT_CAPTURED";
    }

    public void fail(String reason) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Cannot fail order in status " + status);
        }
        status = Status.FAILED;
        sagaState = "FAILED:" + reason;
    }

    public void cancel(String reason) {
        if (status != Status.PENDING && status != Status.CONFIRMED) {
            throw new IllegalStateException("Cannot cancel order in status " + status);
        }
        status = Status.CANCELLED;
        cancelledAt = LocalDateTime.now();
        cancelReason = reason;
        sagaState = "CANCELLED";
    }

    public void ship() {
        if (status != Status.CONFIRMED) {
            throw new IllegalStateException("Cannot ship order in status " + status);
        }
        status = Status.SHIPPED;
    }

    public void deliver() {
        if (status != Status.SHIPPED) {
            throw new IllegalStateException("Cannot deliver order in status " + status);
        }
        status = Status.DELIVERED;
    }

    // ---- getters/setters ----
    public Long getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public void setOrderNumber(String v) { orderNumber = v; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long v) { customerId = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public String getCurrency() { return currency; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal v) { subtotal = v; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal v) { taxAmount = v; }
    public BigDecimal getShippingAmount() { return shippingAmount; }
    public void setShippingAmount(BigDecimal v) { shippingAmount = v; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal v) { totalAmount = v; }
    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String v) { shippingAddress = v; }
    public String getSagaState() { return sagaState; }
    public void setSagaState(String v) { sagaState = v; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public String getCancelReason() { return cancelReason; }
    public List<OrderItem> getItems() { return items; }
}

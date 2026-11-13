package com.commercehub.inventory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * StockReservation — a lease on stock tied to an order.
 *
 * Lifecycle: ACTIVE → FULFILLED (order shipped) | RELEASED (payment failed /
 * order cancelled) | EXPIRED (TTL sweeper returns stock).
 *
 * The (order_id, product_id, status) unique constraint is the DB-level
 * idempotency net: a redelivered order.created can't double-reserve.
 */
@Entity
@Table(name = "stock_reservations")
public class StockReservation {

    public enum Status { ACTIVE, FULFILLED, RELEASED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_no", nullable = false, unique = true, length = 40)
    private String reservationNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** Reservations are leases — a stuck saga can't hold stock forever. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public boolean isActive() { return status == Status.ACTIVE; }

    // ---- getters/setters ----
    public Long getId() { return id; }
    public String getReservationNo() { return reservationNo; }
    public void setReservationNo(String v) { reservationNo = v; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long v) { orderId = v; }
    public Long getProductId() { return productId; }
    public void setProductId(Long v) { productId = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { quantity = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime v) { expiresAt = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}

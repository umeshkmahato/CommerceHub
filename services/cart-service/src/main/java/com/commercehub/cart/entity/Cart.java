package com.commercehub.cart.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cart aggregate root — one per customer (uk_carts_customer).
 *
 * Status lifecycle: ACTIVE → CONVERTED (order placed) | ABANDONED (expiry).
 * CONVERTED carts are kept briefly for "reorder" UX, then purged.
 */
@Entity
@Table(name = "carts")
public class Cart {

    public enum Status { ACTIVE, CONVERTED, ABANDONED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical ref to user-service — no FK (database-per-service). */
    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @OneToMany(mappedBy = "cart", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        expiresAt = createdAt.plusDays(30);   // abandoned-cart horizon
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public void markConverted() { status = Status.CONVERTED; }

    // ---- getters/setters ----
    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public List<CartItem> getItems() { return items; }
}

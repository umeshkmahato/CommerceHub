package com.commercehub.inventory.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * StockLevel — on-hand vs reserved quantities per product+warehouse.
 *
 * quantityAvailable is a GENERATED column in MySQL — we never write it,
 * so it can never drift from (on_hand - reserved). The CHECK constraint
 * guarantees reserved <= on_hand even under direct SQL.
 *
 * PESSIMISTIC_WRITE lock usage: every stock mutation goes through
 * StockRepository.findForUpdate which issues SELECT ... FOR UPDATE.
 */
@Entity
@Table(name = "stock_levels")
public class StockLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(name = "warehouse_code", nullable = false, length = 20)
    private String warehouseCode = "WH-01";

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "quantity_reserved", nullable = false)
    private int quantityReserved;

    /** Read-only mirror of the generated column (mapped for queries). */
    @Column(name = "quantity_available", insertable = false, updatable = false)
    private int quantityAvailable;

    @Column(name = "reorder_point", nullable = false)
    private int reorderPoint = 5;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() { updatedAt = LocalDateTime.now(); }

    /** Business helpers — keep the invariant logic next to the data. */
    public int available() { return quantityOnHand - quantityReserved; }
    public boolean hasStock(int qty) { return available() >= qty; }

    public void reserve(int qty) {
        if (!hasStock(qty)) {
            throw new IllegalStateException(
                    "Insufficient stock for product " + productId);
        }
        quantityReserved += qty;
    }

    public void release(int qty) {
        quantityReserved = Math.max(0, quantityReserved - qty);
    }

    // ---- getters/setters ----
    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getWarehouseCode() { return warehouseCode; }
    public int getQuantityOnHand() { return quantityOnHand; }
    public void setQuantityOnHand(int v) { quantityOnHand = v; }
    public int getQuantityReserved() { return quantityReserved; }
    public int getQuantityAvailable() { return quantityAvailable; }
    public int getReorderPoint() { return reorderPoint; }
    public void setReorderPoint(int v) { reorderPoint = v; }
    public Long getVersion() { return version; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

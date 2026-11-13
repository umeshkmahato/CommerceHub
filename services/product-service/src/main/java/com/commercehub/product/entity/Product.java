package com.commercehub.product.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Product — the aggregate root of the catalog.
 *
 * Key JPA decisions demonstrated here:
 *  - @Version gives OPTIMISTIC locking: two admins editing the same product
 *    can't silently overwrite each other; the loser gets OptimisticLockException.
 *  - images is LAZY + mappedBy: we never load images unless the query asks
 *    for them (JOIN FETCH / @EntityGraph) — avoids the N+1 trap.
 *  - attributes is a JSON column mapped as Map — flexible specs without EAV tables.
 *  - deletedAt implements SOFT DELETE: orders reference products forever,
 *    so a hard delete would corrupt history/audit.
 */
@Entity
@Table(name = "products")
public class Product {

    /** Lifecycle states — mirrored by CHECK constraint in V1 migration. */
    public enum Status { DRAFT, ACTIVE, INACTIVE, DISCONTINUED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 120)
    private String brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /** Customer-facing price. DECIMAL maps to BigDecimal — never double. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "compare_at_price", precision = 12, scale = 2)
    private BigDecimal compareAtPrice;

    /** Internal cost — NEVER exposed in DTOs (see ProductMapper). */
    @Column(name = "cost_price", precision = 12, scale = 2)
    private BigDecimal costPrice;

    @Enumerated(EnumType.STRING)   // store 'ACTIVE', not ordinal 1 — stable across reordering
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** Flexible product specs (color, size, material...) stored as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private Map<String, Object> attributes = new HashMap<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Soft delete — keeps referential history intact for orders/reports. */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
        this.status = Status.DISCONTINUED;
    }

    public boolean isDeleted() { return deletedAt != null; }

    /** Business helper: can this product be added to a cart right now? */
    public boolean isPurchasable() {
        return status == Status.ACTIVE && !isDeleted();
    }

    // ---- getters/setters ----
    public Long getId() { return id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getCompareAtPrice() { return compareAtPrice; }
    public void setCompareAtPrice(BigDecimal compareAtPrice) { this.compareAtPrice = compareAtPrice; }
    public BigDecimal getCostPrice() { return costPrice; }
    public void setCostPrice(BigDecimal costPrice) { this.costPrice = costPrice; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public List<ProductImage> getImages() { return images; }
}

package com.commercehub.product.repository;

import com.commercehub.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Product queries — every method is designed around the storefront's hot paths.
 *
 * N+1 avoidance strategy:
 *  - @EntityGraph(attributePaths = "images") fetches images in the SAME SQL
 *    (JOIN or subsequent select per Hibernate's entity graph impl), so the
 *    mapper can read product.getImages() inside the @Transactional service
 *    method without lazy-init explosions.
 *  - Detail fetch uses JOIN FETCH explicitly so the graph is obvious in code.
 *
 * Search uses LOWER(...) LIKE for portability; the MySQL FULLTEXT index from
 * V1 still accelerates name/description MATCH queries if we later add a
 * native-search method. LIKE with a leading wildcard can't use a B-tree —
 * documented trade-off: fine at catalog scale, move to ES at 1M+ SKUs.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** SKU uniqueness check before insert (also enforced by DB constraint). */
    boolean existsBySku(String sku);

    /**
     * Storefront listing: ACTIVE products only, never soft-deleted.
     * EntityGraph eagerly loads images + category.name path is lazy — the
     * mapper reads category inside the open session (service is @Transactional).
     */
    @EntityGraph(attributePaths = {"images", "category"})
    Page<Product> findByStatusAndDeletedAtIsNull(Product.Status status, Pageable pageable);

    /** Category browse page with optional price band. */
    @EntityGraph(attributePaths = {"images", "category"})
    Page<Product> findByCategoryIdAndStatusAndDeletedAtIsNullAndPriceBetween(
            Long categoryId, Product.Status status,
            BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable);

    /**
     * Faceted search: query text + category + price range + status.
     * COALESCE lets callers pass NULL min/max meaning "unbounded".
     */
    @Query("""
        SELECT p FROM Product p
        WHERE p.status = :status AND p.deletedAt IS NULL
          AND (:categoryId IS NULL OR p.category.id = :categoryId)
          AND (:minPrice IS NULL OR p.price >= :minPrice)
          AND (:maxPrice IS NULL OR p.price <= :maxPrice)
          AND (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(p.brand) LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    @EntityGraph(attributePaths = {"images", "category"})
    Page<Product> search(@Param("q") String q,
                         @Param("categoryId") Long categoryId,
                         @Param("minPrice") BigDecimal minPrice,
                         @Param("maxPrice") BigDecimal maxPrice,
                         @Param("status") Product.Status status,
                         Pageable pageable);

    /**
     * Product detail — explicit JOIN FETCH for images so the response can be
     * built with zero additional round-trips.
     */
    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.images WHERE p.id = :id")
    Optional<Product> findByIdWithImages(@Param("id") Long id);

    /** Admin listing: everything including drafts, newest first. */
    @EntityGraph(attributePaths = {"images", "category"})
    Page<Product> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

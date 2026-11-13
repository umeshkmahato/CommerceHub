package com.commercehub.inventory.repository;

import com.commercehub.inventory.entity.StockLevel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockLevelRepository extends JpaRepository<StockLevel, Long> {

    List<StockLevel> findByProductId(Long productId);

    /**
     * THE critical query: SELECT ... FOR UPDATE.
     *
     * Pessimistic write lock serializes all stock mutations for a product —
     * a second transaction waits until the first commits. This is what makes
     * overselling impossible even under flash-sale concurrency. The cost:
     * throughput on a hot SKU is capped at ~1 tx/commit-latency; acceptable
     * because per-product ordering is inherently serial anyway.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockLevel s WHERE s.productId = :productId " +
           "AND s.warehouseCode = :warehouse ORDER BY s.id")
    List<StockLevel> findForUpdate(@Param("productId") Long productId,
                                   @Param("warehouse") String warehouse);

    Optional<StockLevel> findByProductIdAndWarehouseCode(Long productId, String warehouse);
}

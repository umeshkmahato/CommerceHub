package com.commercehub.inventory.repository;

import com.commercehub.inventory.entity.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderId(Long orderId);

    List<StockReservation> findByOrderIdAndStatus(Long orderId, StockReservation.Status status);

    /** Idempotency probe: does an ACTIVE reservation already exist? */
    boolean existsByOrderIdAndProductIdAndStatus(Long orderId, Long productId,
                                               StockReservation.Status status);

    /** Expiry sweeper: ACTIVE reservations past their lease. */
    @Query("SELECT r FROM StockReservation r WHERE r.status = 'ACTIVE' AND r.expiresAt < :now")
    List<StockReservation> findExpiredActive(@Param("now") LocalDateTime now);
}

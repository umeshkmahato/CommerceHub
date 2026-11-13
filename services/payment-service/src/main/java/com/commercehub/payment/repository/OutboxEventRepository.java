package com.commercehub.payment.repository;

import com.commercehub.payment.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, java.util.UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL " +
           "AND e.availableAt <= :now ORDER BY e.occurredAt")
    List<OutboxEvent> findUnpublishedForUpdate(@Param("now") LocalDateTime now);
}

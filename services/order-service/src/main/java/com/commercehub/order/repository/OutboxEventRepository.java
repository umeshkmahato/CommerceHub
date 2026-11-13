package com.commercehub.order.repository;

import com.commercehub.order.entity.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL " +
           "AND e.availableAt <= :now ORDER BY e.occurredAt")
    List<OutboxEvent> findUnpublishedForUpdate(@Param("now") LocalDateTime now);
}

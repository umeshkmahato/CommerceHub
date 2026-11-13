package com.commercehub.inventory.repository;

import com.commercehub.inventory.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * Relay poll: unpublished, due-for-retry events, oldest first.
     * Pessimistic lock prevents two relay instances (during rolling deploy)
     * from publishing the same event concurrently.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL " +
           "AND e.availableAt <= :now ORDER BY e.occurredAt")
    List<OutboxEvent> findUnpublishedForUpdate(@Param("now") LocalDateTime now);
}

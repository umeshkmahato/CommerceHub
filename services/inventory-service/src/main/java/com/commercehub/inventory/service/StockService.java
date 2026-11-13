package com.commercehub.inventory.service;

import com.commercehub.inventory.entity.OutboxEvent;
import com.commercehub.inventory.entity.StockLevel;
import com.commercehub.inventory.entity.StockReservation;
import com.commercehub.inventory.event.InventoryEvents.InventoryFailedEvent;
import com.commercehub.inventory.event.InventoryEvents.InventoryReservedEvent;
import com.commercehub.inventory.event.InventoryEvents.OrderCreatedEvent;
import com.commercehub.inventory.event.InventoryEvents.OrderCancelledEvent;
import com.commercehub.inventory.repository.OutboxEventRepository;
import com.commercehub.inventory.repository.StockLevelRepository;
import com.commercehub.inventory.repository.StockReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Stock reservation engine — the overselling-critical path.
 *
 * Algorithm for order.created (all inside ONE transaction):
 *  1. For each order item, SELECT ... FOR UPDATE the stock row
 *     (pessimistic lock — serializes concurrent reservations).
 *  2. Check available >= requested; if ANY item fails → reserve nothing
 *     (atomic: no partial reservations leak), emit inventory.failed.
 *  3. On success: bump quantity_reserved, insert ACTIVE reservation rows
 *     (30-min lease), write inventory.reserved to the OUTBOX — all in the
 *     same DB transaction, so stock state and event can never diverge.
 *
 * Compensating actions: order.cancelled / payment failure → release()
 * decrements reserved and marks reservations RELEASED.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final String WAREHOUSE = "WH-01";
    private static final int RESERVATION_TTL_MINUTES = 30;

    private final StockLevelRepository stockRepo;
    private final StockReservationRepository reservationRepo;
    private final OutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public StockService(StockLevelRepository stockRepo,
                        StockReservationRepository reservationRepo,
                        OutboxEventRepository outboxRepo,
                        ObjectMapper objectMapper) {
        this.stockRepo = stockRepo;
        this.reservationRepo = reservationRepo;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
    }

    // ------------------------------ reserve ------------------------------

    /**
     * Reserve stock for an order. Idempotent: if an ACTIVE reservation for
     * (orderId, productId) already exists we skip that line — the consumer
     * ALSO dedupes by eventId, this is the second safety net.
     */
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        List<OrderCreatedEvent.OrderItem> items = event.items();
        List<InventoryFailedEvent.FailureLine> failures = new ArrayList<>();
        List<InventoryReservedEvent.ReservationLine> reserved = new ArrayList<>();

        // Phase 1: validate ALL items first (no partial reservations).
        Map<Long, StockLevel> locked = new LinkedHashMap<>();
        for (var item : items) {
            if (reservationRepo.existsByOrderIdAndProductIdAndStatus(
                    event.orderId(), item.productId(), StockReservation.Status.ACTIVE)) {
                continue;   // already reserved — idempotent replay
            }
            List<StockLevel> levels = stockRepo.findForUpdate(item.productId(), WAREHOUSE);
            StockLevel level = levels.stream().findFirst().orElse(null);
            if (level == null || !level.hasStock(item.quantity())) {
                failures.add(new InventoryFailedEvent.FailureLine(
                        item.productId(), item.sku(), item.quantity(),
                        level != null ? level.available() : 0, "INSUFFICIENT_STOCK"));
            } else {
                locked.put(item.productId(), level);
            }
        }

        if (!failures.isEmpty()) {
            publishFailed(event, failures);
            log.warn("Order {} reservation FAILED: {}", event.orderId(), failures);
            return;   // tx commits only the outbox event — no stock mutated
        }

        // Phase 2: all checks passed → apply reservations atomically.
        for (var item : items) {
            if (reservationRepo.existsByOrderIdAndProductIdAndStatus(
                    event.orderId(), item.productId(), StockReservation.Status.ACTIVE)) {
                continue;
            }
            StockLevel level = locked.get(item.productId());
            if (level == null) continue;

            level.reserve(item.quantity());
            stockRepo.save(level);

            StockReservation res = new StockReservation();
            res.setReservationNo("RES-" + event.orderId() + "-" + item.productId());
            res.setOrderId(event.orderId());
            res.setProductId(item.productId());
            res.setQuantity(item.quantity());
            res.setExpiresAt(LocalDateTime.now().plus(RESERVATION_TTL_MINUTES, ChronoUnit.MINUTES));
            reservationRepo.save(res);

            reserved.add(new InventoryReservedEvent.ReservationLine(
                    item.productId(), item.sku(), item.quantity(), res.getReservationNo()));
        }

        if (!reserved.isEmpty()) {
            publishReserved(event, reserved);
            log.info("Order {} reserved {} line(s)", event.orderId(), reserved.size());
        }
    }

    // ------------------------------ release ------------------------------

    /** Release all ACTIVE reservations for an order (cancel/payment-fail). */
    @Transactional
    public void releaseForOrder(Long orderId, String reason) {
        List<StockReservation> active =
                reservationRepo.findByOrderIdAndStatus(orderId, StockReservation.Status.ACTIVE);
        for (StockReservation res : active) {
            stockRepo.findForUpdate(res.getProductId(), WAREHOUSE).stream().findFirst()
                    .ifPresent(level -> {
                        level.release(res.getQuantity());
                        stockRepo.save(level);
                    });
            res.setStatus(StockReservation.Status.RELEASED);
            reservationRepo.save(res);
            log.info("Released reservation {} for order {} ({})",
                    res.getReservationNo(), orderId, reason);
        }
    }

    /** Expiry sweeper — returns stock for reservations past their lease. */
    @Transactional
    public int expireStaleReservations() {
        List<StockReservation> expired = reservationRepo.findExpiredActive(LocalDateTime.now());
        for (StockReservation res : expired) {
            releaseForOrder(res.getOrderId(), "RESERVATION_EXPIRED");
        }
        return expired.size();
    }

    // ------------------------------ outbox ------------------------------

    private void publishReserved(OrderCreatedEvent ev,
                                 List<InventoryReservedEvent.ReservationLine> lines) {
        var payload = new InventoryReservedEvent(UUID.randomUUID(), "inventory.reserved",
                ev.orderId(), ev.totalAmount(), ev.customerId(), lines,
                java.time.Instant.now(), 1);
        Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
        outboxRepo.save(OutboxEvent.of("RESERVATION", ev.orderId(),
                "inventory.reserved", map));
    }

    private void publishFailed(OrderCreatedEvent ev,
                               List<InventoryFailedEvent.FailureLine> failures) {
        var payload = new InventoryFailedEvent(UUID.randomUUID(), "inventory.failed",
                ev.orderId(), failures, java.time.Instant.now(), 1);
        Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
        outboxRepo.save(OutboxEvent.of("RESERVATION", ev.orderId(),
                "inventory.failed", map));
    }
}

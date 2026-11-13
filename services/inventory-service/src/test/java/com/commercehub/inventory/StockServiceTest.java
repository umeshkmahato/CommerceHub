package com.commercehub.inventory;

import com.commercehub.inventory.entity.OutboxEvent;
import com.commercehub.inventory.entity.StockLevel;
import com.commercehub.inventory.entity.StockReservation;
import com.commercehub.inventory.event.InventoryEvents.OrderCreatedEvent;
import com.commercehub.inventory.repository.OutboxEventRepository;
import com.commercehub.inventory.repository.StockLevelRepository;
import com.commercehub.inventory.repository.StockReservationRepository;
import com.commercehub.inventory.service.StockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StockService unit tests — the overselling-critical logic.
 * Verifies: insufficient stock → failure event + NO mutations;
 * sufficient stock → reserve + outbox event; idempotent replay skips.
 */
@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock StockLevelRepository stockRepo;
    @Mock StockReservationRepository reservationRepo;
    @Mock OutboxEventRepository outboxRepo;
    @org.mockito.Spy ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks StockService stockService;

    private StockLevel stock;
    private OrderCreatedEvent orderEvent;

    @BeforeEach
    void setUp() {
        stock = new StockLevel();
        org.springframework.test.util.ReflectionTestUtils.setField(stock, "id", 1L);
        stock.setProductId(100L);
        stock.setSku("SKU-1");
        stock.setQuantityOnHand(10);
        org.springframework.test.util.ReflectionTestUtils.setField(stock, "quantityReserved", 0);

        orderEvent = new OrderCreatedEvent(
                UUID.randomUUID(), "order.created", 55L, "ORD-1", 42L,
                new BigDecimal("29.97"),
                List.of(new OrderCreatedEvent.OrderItem(
                        100L, "SKU-1", "Widget", 3, new BigDecimal("9.99"))),
                Instant.now(), 1);
    }

    private void stubLockAndNoExisting() {
        when(stockRepo.findForUpdate(100L, "WH-01")).thenReturn(List.of(stock));
        when(reservationRepo.existsByOrderIdAndProductIdAndStatus(
                eq(55L), eq(100L), eq(StockReservation.Status.ACTIVE))).thenReturn(false);
        // lenient: failure-path tests never reach the save calls
        lenient().when(stockRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reservationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void sufficientStock_reservesAndWritesOutboxEvent() {
        stubLockAndNoExisting();

        stockService.handleOrderCreated(orderEvent);

        assertEquals(3, stock.getQuantityReserved());
        verify(reservationRepo).save(argThat(r ->
                r.getOrderId().equals(55L) && r.getQuantity() == 3));
        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(cap.capture());
        assertEquals("inventory.reserved", cap.getValue().getEventType());
    }

    @Test
    void insufficientStock_failsWithoutMutatingStock() {
        stubLockAndNoExisting();
        stock.setQuantityOnHand(2);   // only 2 available, order wants 3

        stockService.handleOrderCreated(orderEvent);

        assertEquals(0, stock.getQuantityReserved());          // untouched
        verify(reservationRepo, never()).save(any());          // no reservation
        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(cap.capture());
        assertEquals("inventory.failed", cap.getValue().getEventType());
    }

    @Test
    void duplicateEvent_existingReservation_skipsLine() {
        // Replay: reservation already ACTIVE → no double-reserve.
        when(reservationRepo.existsByOrderIdAndProductIdAndStatus(
                eq(55L), eq(100L), eq(StockReservation.Status.ACTIVE))).thenReturn(true);

        stockService.handleOrderCreated(orderEvent);

        verify(stockRepo, never()).findForUpdate(anyLong(), anyString());
        verify(outboxRepo, never()).save(any());
    }

    @Test
    void releaseForOrder_restoresStockAndMarksReleased() {
        StockReservation res = new StockReservation();
        res.setOrderId(55L);
        res.setProductId(100L);
        res.setQuantity(3);
        org.springframework.test.util.ReflectionTestUtils.setField(stock, "quantityReserved", 3);

        when(reservationRepo.findByOrderIdAndStatus(55L, StockReservation.Status.ACTIVE))
                .thenReturn(List.of(res));
        when(stockRepo.findForUpdate(100L, "WH-01")).thenReturn(List.of(stock));
        when(stockRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reservationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stockService.releaseForOrder(55L, "PAYMENT_FAILED");

        assertEquals(0, stock.getQuantityReserved());
        assertEquals(StockReservation.Status.RELEASED, res.getStatus());
    }

    @Test
    void multiItem_oneFails_rejectsEntireOrder() {
        // Order has 2 items; second has no stock → NOTHING reserved (atomic).
        var twoItemOrder = new OrderCreatedEvent(
                UUID.randomUUID(), "order.created", 56L, "ORD-2", 42L,
                new BigDecimal("20.00"),
                List.of(
                        new OrderCreatedEvent.OrderItem(100L, "SKU-1", "W", 1, BigDecimal.TEN),
                        new OrderCreatedEvent.OrderItem(200L, "SKU-2", "X", 1, BigDecimal.TEN)),
                Instant.now(), 1);

        StockLevel good = stock;
        when(stockRepo.findForUpdate(100L, "WH-01")).thenReturn(List.of(good));
        when(stockRepo.findForUpdate(200L, "WH-01")).thenReturn(List.of());  // no stock row
        when(reservationRepo.existsByOrderIdAndProductIdAndStatus(anyLong(), anyLong(), any()))
                .thenReturn(false);
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stockService.handleOrderCreated(twoItemOrder);

        assertEquals(0, good.getQuantityReserved());   // good item NOT reserved either
        verify(reservationRepo, never()).save(any());
    }
}

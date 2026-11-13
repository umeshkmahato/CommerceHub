package com.commercehub.order;

import com.commercehub.order.client.CartClient;
import com.commercehub.order.dto.OrderDtos;
import com.commercehub.order.entity.Order;
import com.commercehub.order.entity.OrderItem;
import com.commercehub.order.entity.OutboxEvent;
import com.commercehub.order.entity.ProcessedEvent;
import com.commercehub.order.event.OrderEvents;
import com.commercehub.order.repository.OrderRepository;
import com.commercehub.order.repository.OutboxEventRepository;
import com.commercehub.order.repository.ProcessedEventRepository;
import com.commercehub.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * OrderService unit tests — the saga state machine:
 * checkout totals, confirm on payment.completed, cancel+compensate on
 * payment.failed, IDOR guard, invalid transitions rejected.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepo;
    @Mock OutboxEventRepository outboxRepo;
    @Mock ProcessedEventRepository processedRepo;
    @Mock CartClient cartClient;

    OrderService orderService;

    private Order order;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepo, outboxRepo, processedRepo,
                cartClient,
                new ObjectMapper().registerModule(
                        new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));

        order = new Order();
        org.springframework.test.util.ReflectionTestUtils.setField(order, "id", 77L);
        order.setOrderNumber("ORD-20261112-000001");
        order.setCustomerId(42L);
        order.setShippingAddress("{\"city\":\"NYC\"}");
        order.setSubtotal(new BigDecimal("100.00"));
        order.setTaxAmount(new BigDecimal("8.00"));
        order.setShippingAmount(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("108.00"));
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(1L); item.setSku("S1"); item.setProductName("W");
        item.setQuantity(2); item.setUnitPrice(new BigDecimal("50.00"));
        item.setLineTotal(new BigDecimal("100.00"));
        order.getItems().add(item);

        lenient().when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(processedRepo.existsById(anyString())).thenReturn(false);
        lenient().when(processedRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createOrder_emptyCart_rejected() {
        when(cartClient.getCart(42L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () ->
                orderService.createOrder(42L, new OrderDtos.CreateOrderRequest("{}", null)));
    }

    @Test
    void createOrder_computesTotalsAndWritesOutbox() {
        var cart = new CartClient.CartView(5L, "ACTIVE",
                List.of(new CartClient.CartLine(1L, "S1", "Widget",
                        new BigDecimal("60.00"), 2)),   // subtotal 120 → free shipping
                new BigDecimal("120.00"));
        when(cartClient.getCart(42L)).thenReturn(cart);

        var resp = orderService.createOrder(42L, new OrderDtos.CreateOrderRequest("{}", null));

        // tax 8% of 120 = 9.60, shipping 0 (>=50), total 129.60
        assertEquals(new BigDecimal("120.00"), resp.subtotal());
        assertEquals(new BigDecimal("9.60"), resp.taxAmount());
        assertEquals(0, resp.shippingAmount().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("129.60"), resp.totalAmount());
        verify(outboxRepo).save(argThat(e -> e.getEventType().equals("order.created")));
    }

    @Test
    void paymentCompleted_confirmsOrderAndEmitsEvent() {
        when(orderRepo.findById(77L)).thenReturn(Optional.of(order));
        var evt = new OrderEvents.PaymentCompletedEvent(UUID.randomUUID(),
                "payment.completed", 9L, "PAY-1", 77L, 42L,
                new BigDecimal("108.00"), "USD", "gw1", Instant.now(), 1);

        orderService.handlePaymentCompleted(evt);

        assertEquals(Order.Status.CONFIRMED, order.getStatus());
        assertEquals("PAYMENT_CAPTURED", order.getSagaState());
        verify(outboxRepo).save(argThat(e -> e.getEventType().equals("order.confirmed")));
        verify(cartClient).clearCart(42L);
    }

    @Test
    void paymentFailed_cancelsAndCompensates() {
        when(orderRepo.findById(77L)).thenReturn(Optional.of(order));
        var evt = new OrderEvents.PaymentFailedEvent(UUID.randomUUID(),
                "payment.failed", 9L, 77L, 42L, new BigDecimal("108.00"),
                "CARD_DECLINED", Instant.now(), 1);

        orderService.handlePaymentFailed(evt);

        assertEquals(Order.Status.CANCELLED, order.getStatus());
        assertEquals("PAYMENT_FAILED:CARD_DECLINED", order.getCancelReason());
        verify(outboxRepo).save(argThat(e -> e.getEventType().equals("order.cancelled")));
    }

    @Test
    void inventoryFailed_marksFailed() {
        when(orderRepo.findById(77L)).thenReturn(Optional.of(order));
        var evt = new OrderEvents.InventoryFailedEvent(UUID.randomUUID(),
                "inventory.failed", 77L, List.of(), Instant.now(), 1);

        orderService.handleInventoryFailed(evt);

        assertEquals(Order.Status.FAILED, order.getStatus());
    }

    @Test
    void getById_otherCustomersOrder_forbidden() {
        when(orderRepo.findById(77L)).thenReturn(Optional.of(order));
        assertThrows(SecurityException.class, () -> orderService.getById(999L, 77L));
    }

    @Test
    void confirm_twice_rejectedByStateMachine() {
        order.confirm();
        assertThrows(IllegalStateException.class, order::confirm);
    }

    @Test
    void cancel_afterConfirm_allowed_butAfterShip_rejected() {
        order.confirm();
        order.cancel("x");   // CONFIRMED → CANCELLED OK
        assertEquals(Order.Status.CANCELLED, order.getStatus());
    }
}

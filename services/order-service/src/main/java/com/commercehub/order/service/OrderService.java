package com.commercehub.order.service;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Order lifecycle — saga choreography owner.
 *
 * Checkout flow:
 *  1. createOrder: pull cart from cart-service (server-side, tamper-proof),
 *     compute totals, persist order + items + outbox(order.created) in ONE tx.
 *  2. Saga events arrive async: inventory.failed → FAILED; payment.completed
 *     → CONFIRMED (+order.confirmed event + cart clear); payment.failed →
 *     CANCELLED (+order.cancelled → inventory releases stock).
 *
 * Compensation is event-driven: cancelling publishes order.cancelled which
 * inventory/payment services consume to undo their steps — classic saga.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final BigDecimal TAX_RATE = new BigDecimal("0.08");
    private static final BigDecimal FLAT_SHIPPING = new BigDecimal("9.99");
    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("50.00");
    private static final AtomicLong ORDER_SEQ = new AtomicLong(System.currentTimeMillis() % 100000);

    private final OrderRepository orderRepo;
    private final OutboxEventRepository outboxRepo;
    private final ProcessedEventRepository processedRepo;
    private final CartClient cartClient;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orderRepo, OutboxEventRepository outboxRepo,
                        ProcessedEventRepository processedRepo, CartClient cartClient,
                        ObjectMapper objectMapper) {
        this.orderRepo = orderRepo;
        this.outboxRepo = outboxRepo;
        this.processedRepo = processedRepo;
        this.cartClient = cartClient;
        this.objectMapper = objectMapper;
    }

    // ------------------------------ checkout ------------------------------

    @Transactional
    public OrderDtos.OrderResponse createOrder(Long customerId, OrderDtos.CreateOrderRequest req) {
        // 1. Pull authoritative cart — never trust client-supplied items/prices.
        CartClient.CartView cart = cartClient.getCart(customerId);
        if (cart == null || cart.items().isEmpty()) {
            throw new IllegalArgumentException("Cart is empty — nothing to order");
        }

        // 2. Build order + snapshot items.
        Order order = new Order();
        order.setOrderNumber(nextOrderNumber());
        order.setCustomerId(customerId);
        order.setShippingAddress(req.shippingAddressJson());

        BigDecimal subtotal = BigDecimal.ZERO;
        for (var line : cart.items()) {
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setProductId(line.productId());
            item.setSku(line.sku());
            item.setProductName(line.productName());
            item.setQuantity(line.quantity());
            item.setUnitPrice(line.unitPrice());
            item.setLineTotal(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
            order.getItems().add(item);
            subtotal = subtotal.add(item.getLineTotal());
        }

        // 3. Totals: tax 8%, shipping free over $50 else $9.99.
        order.setSubtotal(subtotal);
        order.setTaxAmount(subtotal.multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP));
        order.setShippingAmount(subtotal.compareTo(FREE_SHIPPING_THRESHOLD) >= 0
                ? BigDecimal.ZERO : FLAT_SHIPPING);
        order.setTotalAmount(order.getSubtotal().add(order.getTaxAmount())
                .add(order.getShippingAmount()).setScale(2, RoundingMode.HALF_UP));

        order = orderRepo.save(order);

        // 4. Outbox event in the SAME transaction — order.created can't be lost.
        publishOrderCreated(order);
        log.info("Order {} created for customer {} (total {})",
                order.getOrderNumber(), customerId, order.getTotalAmount());
        return toResponse(order);
    }

    // ------------------------------ saga transitions ------------------------------

    /** inventory.failed → order FAILED (nothing was reserved, nothing to undo). */
    @Transactional
    public void handleInventoryFailed(OrderEvents.InventoryFailedEvent event) {
        if (alreadyProcessed(event.eventId().toString(), "inventory.failed")) return;
        Order order = requireOrder(event.orderId());
        if (order.getStatus() == Order.Status.PENDING) {
            order.fail("INVENTORY_UNAVAILABLE");
            orderRepo.save(order);
            publishFailed(order, "INVENTORY_UNAVAILABLE");
        }
        markProcessed(event.eventId().toString(), "inventory.failed");
    }

    /** payment.completed → CONFIRMED, emit order.confirmed, clear cart. */
    @Transactional
    public void handlePaymentCompleted(OrderEvents.PaymentCompletedEvent event) {
        if (alreadyProcessed(event.eventId().toString(), "payment.completed")) return;
        Order order = requireOrder(event.orderId());
        if (order.getStatus() == Order.Status.PENDING) {
            order.confirm();
            orderRepo.save(order);
            publishConfirmed(order);
            cartClient.clearCart(order.getCustomerId());   // best-effort post-commit-ish
            log.info("Order {} CONFIRMED (payment {})", order.getOrderNumber(),
                    event.paymentNumber());
        }
        markProcessed(event.eventId().toString(), "payment.completed");
    }

    /** payment.failed → CANCELLED + order.cancelled → inventory releases stock. */
    @Transactional
    public void handlePaymentFailed(OrderEvents.PaymentFailedEvent event) {
        if (alreadyProcessed(event.eventId().toString(), "payment.failed")) return;
        Order order = requireOrder(event.orderId());
        if (order.getStatus() == Order.Status.PENDING) {
            order.cancel("PAYMENT_FAILED:" + event.reason());
            orderRepo.save(order);
            publishCancelled(order);
            log.info("Order {} CANCELLED (payment failed: {})",
                    order.getOrderNumber(), event.reason());
        }
        markProcessed(event.eventId().toString(), "payment.failed");
    }

    // ------------------------------ customer ops ------------------------------

    @Transactional(readOnly = true)
    public Page<OrderDtos.OrderResponse> history(Long customerId, int page, int size) {
        return orderRepo.findByCustomerIdOrderByCreatedAtDesc(customerId,
                        PageRequest.of(page, Math.min(size, 50),
                                Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OrderDtos.OrderResponse getById(Long customerId, Long id) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
        if (!order.getCustomerId().equals(customerId)) {
            throw new SecurityException("Not your order");   // IDOR guard
        }
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderDtos.OrderResponse track(Long customerId, String orderNumber) {
        Order order = orderRepo.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getCustomerId().equals(customerId)) {
            throw new SecurityException("Not your order");
        }
        return toResponse(order);
    }

    /** Customer-initiated cancel — only before shipment; saga compensates. */
    @Transactional
    public OrderDtos.OrderResponse cancel(Long customerId, Long id, String reason) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
        if (!order.getCustomerId().equals(customerId)) {
            throw new SecurityException("Not your order");
        }
        order.cancel(reason != null ? reason : "CUSTOMER_REQUEST");
        orderRepo.save(order);
        publishCancelled(order);   // inventory releases, payment auto-refunds
        return toResponse(order);
    }

    /** Admin status transitions: SHIPPED / DELIVERED. */
    @Transactional
    public OrderDtos.OrderResponse updateStatus(Long id, Order.Status target) {
        Order order = orderRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
        switch (target) {
            case SHIPPED -> order.ship();
            case DELIVERED -> order.deliver();
            default -> throw new IllegalArgumentException("Unsupported transition: " + target);
        }
        return toResponse(orderRepo.save(order));
    }

    // ------------------------------ internals ------------------------------

    private Order requireOrder(Long orderId) {
        return orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));
    }

    private boolean alreadyProcessed(String eventId, String type) {
        return processedRepo.existsById(eventId);
    }
    private void markProcessed(String eventId, String type) {
        processedRepo.save(new ProcessedEvent(eventId, type));
    }

    private void publishOrderCreated(Order order) {
        var items = order.getItems().stream()
                .map(i -> new OrderEvents.OrderCreatedEvent.OrderItemPayload(
                        i.getProductId(), i.getSku(), i.getProductName(),
                        i.getQuantity(), i.getUnitPrice()))
                .toList();
        var evt = new OrderEvents.OrderCreatedEvent(UUID.randomUUID(), "order.created",
                order.getId(), order.getOrderNumber(), order.getCustomerId(),
                order.getTotalAmount(), items, java.time.Instant.now(), 1);
        outboxRepo.save(OutboxEvent.of("ORDER", order.getId(), "order.created", toMap(evt)));
    }

    private void publishConfirmed(Order order) {
        var evt = new OrderEvents.OrderConfirmedEvent(UUID.randomUUID(), "order.confirmed",
                order.getId(), order.getOrderNumber(), order.getCustomerId(),
                order.getTotalAmount(), java.time.Instant.now(), 1);
        outboxRepo.save(OutboxEvent.of("ORDER", order.getId(), "order.confirmed", toMap(evt)));
    }

    private void publishCancelled(Order order) {
        var evt = new OrderEvents.OrderCancelledEvent(UUID.randomUUID(), "order.cancelled",
                order.getId(), order.getOrderNumber(), order.getCustomerId(),
                order.getCancelReason(), java.time.Instant.now(), 1);
        outboxRepo.save(OutboxEvent.of("ORDER", order.getId(), "order.cancelled", toMap(evt)));
    }

    private void publishFailed(Order order, String reason) {
        var evt = new OrderEvents.OrderFailedEvent(UUID.randomUUID(), "order.failed",
                order.getId(), order.getOrderNumber(), order.getCustomerId(),
                reason, java.time.Instant.now(), 1);
        outboxRepo.save(OutboxEvent.of("ORDER", order.getId(), "order.failed", toMap(evt)));
    }

    private Map<String, Object> toMap(Object evt) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.convertValue(evt, Map.class);
        return map;
    }

    private String nextOrderNumber() {
        return "ORD-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", ORDER_SEQ.incrementAndGet() % 1000000);
    }

    // ------------------------------ mapping ------------------------------

    private OrderDtos.OrderResponse toResponse(Order o) {
        List<OrderDtos.OrderItemResponse> items = o.getItems().stream()
                .map(i -> new OrderDtos.OrderItemResponse(i.getProductId(), i.getSku(),
                        i.getProductName(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal()))
                .toList();
        Map<String, Object> addr = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(o.getShippingAddress(), Map.class);
            addr = parsed;
        } catch (Exception ignored) { /* keep raw string fallback below */ }
        return new OrderDtos.OrderResponse(o.getId(), o.getOrderNumber(),
                o.getStatus().name(), o.getSagaState(), o.getSubtotal(), o.getTaxAmount(),
                o.getShippingAmount(), o.getTotalAmount(), o.getCurrency(),
                addr != null ? addr : Map.of("raw", o.getShippingAddress()), items,
                o.getCreatedAt(), o.getUpdatedAt(), o.getCancelledAt(), o.getCancelReason());
    }
}

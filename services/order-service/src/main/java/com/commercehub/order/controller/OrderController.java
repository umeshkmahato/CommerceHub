package com.commercehub.order.controller;

import com.commercehub.order.dto.OrderDtos;
import com.commercehub.order.entity.Order;
import com.commercehub.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * /api/v1/orders — customer-facing order API.
 * customerId always from gateway X-User-Id header; ownership enforced
 * in the service (IDOR guard on every read/write).
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Checkout, history, tracking, cancel")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Checkout — creates PENDING order, starts saga")
    public ResponseEntity<OrderDtos.OrderResponse> create(
            @RequestHeader("X-User-Id") Long customerId,
            @Valid @RequestBody OrderDtos.CreateOrderRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.createOrder(customerId, req));
    }

    @GetMapping
    @Operation(summary = "My order history (paginated)")
    public Page<OrderDtos.OrderResponse> history(
            @RequestHeader("X-User-Id") Long customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return orderService.history(customerId, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Order detail (owner only)")
    public OrderDtos.OrderResponse get(@RequestHeader("X-User-Id") Long customerId,
                                       @PathVariable Long id) {
        return orderService.getById(customerId, id);
    }

    @GetMapping("/track/{orderNumber}")
    @Operation(summary = "Track order by number")
    public OrderDtos.OrderResponse track(@RequestHeader("X-User-Id") Long customerId,
                                         @PathVariable String orderNumber) {
        return orderService.track(customerId, orderNumber);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel order (PENDING/CONFIRMED only)")
    public OrderDtos.OrderResponse cancel(@RequestHeader("X-User-Id") Long customerId,
                                          @PathVariable Long id,
                                          @RequestBody(required = false) OrderDtos.CancelRequest req) {
        return orderService.cancel(customerId, id, req != null ? req.reason() : null);
    }

    /** Admin fulfillment transitions. */
    @PatchMapping("/admin/{id}/status")
    @Operation(summary = "Update order status (ADMIN): SHIPPED|DELIVERED")
    public OrderDtos.OrderResponse adminStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderDtos.StatusUpdateRequest req,
            @RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        return orderService.updateStatus(id, Order.Status.valueOf(req.status()));
    }

    private void requireAdmin(String roles) {
        if (roles == null || !List.of(roles.split(",")).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN role required");
        }
    }
}

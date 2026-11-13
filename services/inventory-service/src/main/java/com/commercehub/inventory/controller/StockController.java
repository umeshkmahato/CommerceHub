package com.commercehub.inventory.controller;

import com.commercehub.inventory.entity.StockLevel;
import com.commercehub.inventory.entity.StockReservation;
import com.commercehub.inventory.repository.StockLevelRepository;
import com.commercehub.inventory.repository.StockReservationRepository;
import com.commercehub.inventory.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Inventory REST API — internal/admin surface.
 *
 * Public availability checks go through product/cart flows; these endpoints
 * serve ops tooling and service-to-service calls (warehouse restock,
 * reservation inspection). Mutations require X-User-Roles: ADMIN from the
 * gateway, same trust model as product-service.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Stock levels and reservations")
public class StockController {

    private final StockLevelRepository stockRepo;
    private final StockReservationRepository reservationRepo;
    private final StockService stockService;

    public StockController(StockLevelRepository stockRepo,
                           StockReservationRepository reservationRepo,
                           StockService stockService) {
        this.stockRepo = stockRepo;
        this.reservationRepo = reservationRepo;
        this.stockService = stockService;
    }

    public record StockResponse(Long productId, String sku, int onHand,
                                int reserved, int available, int reorderPoint) {}
    public record ReservationResponse(Long id, String reservationNo, Long orderId,
                                      Long productId, int quantity, String status) {}

    @GetMapping("/stock/{productId}")
    @Operation(summary = "Stock level for a product")
    @Transactional(readOnly = true)
    public List<StockResponse> stockForProduct(@PathVariable Long productId) {
        return stockRepo.findByProductId(productId).stream()
                .map(s -> new StockResponse(s.getProductId(), s.getSku(),
                        s.getQuantityOnHand(), s.getQuantityReserved(),
                        s.available(), s.getReorderPoint()))
                .toList();
    }

    @GetMapping("/orders/{orderId}/reservations")
    @Operation(summary = "Reservations held for an order")
    @Transactional(readOnly = true)
    public List<ReservationResponse> reservationsForOrder(@PathVariable Long orderId) {
        return reservationRepo.findByOrderId(orderId).stream()
                .map(r -> new ReservationResponse(r.getId(), r.getReservationNo(),
                        r.getOrderId(), r.getProductId(), r.getQuantity(),
                        r.getStatus().name()))
                .toList();
    }

    /** Ops endpoint: force-release an order's reservations (compensation). */
    @PostMapping("/internal/release/{orderId}")
    @Operation(summary = "Release reservations for an order (service call)")
    public ResponseEntity<Void> release(@PathVariable Long orderId,
                                        @RequestParam(defaultValue = "MANUAL") String reason) {
        stockService.releaseForOrder(orderId, reason);
        return ResponseEntity.noContent().build();
    }
}

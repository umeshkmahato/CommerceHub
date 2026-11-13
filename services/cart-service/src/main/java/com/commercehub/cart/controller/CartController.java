package com.commercehub.cart.controller;

import com.commercehub.cart.dto.CartDtos;
import com.commercehub.cart.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * /api/v1/cart — customer-scoped endpoints.
 *
 * customerId comes from the X-User-Id header injected by the API gateway
 * after JWT validation (the gateway strips any client-supplied value).
 * Cart-service itself doesn't parse JWTs — it trusts the gateway boundary,
 * same pattern as product-service's X-User-Roles.
 */
@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart", description = "Shopping cart operations")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "Get my cart (creates empty if none)")
    public CartDtos.CartResponse getCart(@RequestHeader("X-User-Id") Long customerId) {
        return cartService.getCart(customerId);
    }

    @PostMapping("/items")
    @Operation(summary = "Add product to cart")
    public CartDtos.CartResponse addItem(@RequestHeader("X-User-Id") Long customerId,
                                         @Valid @RequestBody CartDtos.AddItemRequest req) {
        return cartService.addItem(customerId, req);
    }

    @PutMapping("/items/{productId}")
    @Operation(summary = "Set quantity (0 removes)")
    public CartDtos.CartResponse updateQuantity(
            @RequestHeader("X-User-Id") Long customerId,
            @PathVariable Long productId,
            @Valid @RequestBody CartDtos.UpdateQuantityRequest req) {
        return cartService.updateQuantity(customerId, productId, req.quantity());
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove item")
    public CartDtos.CartResponse removeItem(@RequestHeader("X-User-Id") Long customerId,
                                            @PathVariable Long productId) {
        return cartService.removeItem(customerId, productId);
    }

    /** Internal endpoint — called by order-service after successful checkout. */
    @PostMapping("/internal/clear")
    @Operation(summary = "Clear cart after order (service-to-service)")
    public ResponseEntity<Void> clearCart(@RequestParam Long customerId) {
        cartService.clearCart(customerId);
        return ResponseEntity.noContent().build();
    }
}

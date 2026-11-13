package com.commercehub.product.controller;

import com.commercehub.product.dto.ProductDtos;
import com.commercehub.product.dto.ProductResponses;
import com.commercehub.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Product REST API — /api/v1/products.
 *
 * Security model (documented trade-off):
 *  - GET endpoints are PUBLIC through the gateway (storefront browsing).
 *  - Mutations require the gateway-injected `X-User-Roles` header to contain
 *    ADMIN. The gateway strips client-supplied copies of this header, so
 *    only authenticated admins can reach these endpoints. This keeps
 *    product-service free of JWT parsing while still enforcing authz.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Products", description = "Catalog browse, search, admin management")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // ------------------------------ public reads ------------------------------

    @GetMapping("/products")
    @Operation(summary = "Search/browse products",
               description = "q=text, categoryId, minPrice, maxPrice, page, size, sort")
    public ResponseEntity<ProductResponses.PageResponse<ProductResponses.ProductSummaryResponse>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sort) {
        return ResponseEntity.ok(ProductResponses.PageResponse.of(
                productService.search(q, categoryId, minPrice, maxPrice, page, size, sort)));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Get product detail")
    public ProductResponses.ProductResponse getById(@PathVariable Long id) {
        return productService.getById(id);
    }

    @GetMapping("/categories")
    @Operation(summary = "List top-level categories")
    public List<ProductResponses.CategoryResponse> categories() {
        return productService.listCategories();
    }

    // ------------------------------ admin writes ------------------------------

    @PostMapping("/admin/products")
    @Operation(summary = "Create product (ADMIN)")
    public ResponseEntity<ProductResponses.ProductResponse> create(
            @Valid @RequestBody ProductDtos.ProductRequest req,
            @RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(req));
    }

    @PutMapping("/admin/products/{id}")
    @Operation(summary = "Update product (ADMIN)")
    public ProductResponses.ProductResponse update(
            @PathVariable Long id,
            @Valid @RequestBody ProductDtos.ProductUpdateRequest req,
            @RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        return productService.update(id, req);
    }

    @PatchMapping("/admin/products/{id}/status")
    @Operation(summary = "Change product status / deactivate (ADMIN)")
    public ResponseEntity<Void> changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ProductDtos.StatusChangeRequest req,
            @RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        if (req.status() == ProductDtos.ProductStatusEnum.DISCONTINUED) {
            productService.deactivate(id);
        }
        // Other status transitions handled via update path in full impl;
        // deactivate covers the saga-relevant "no longer purchasable" case.
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/products")
    @Operation(summary = "Admin product listing, all statuses (ADMIN)")
    public ProductResponses.PageResponse<ProductResponses.ProductSummaryResponse> listAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-User-Roles") String roles) {
        requireAdmin(roles);
        return ProductResponses.PageResponse.of(productService.listAll(page, size));
    }

    /** Gateway injects roles as comma-separated; ADMIN must be present. */
    private void requireAdmin(String roles) {
        if (roles == null || !List.of(roles.split(",")).contains("ADMIN")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "ADMIN role required");
        }
    }
}

package com.commercehub.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbound DTOs — the API contract. Entities NEVER leave the service:
 * exposing JPA entities leaks lazy-loading bugs, internal fields (costPrice),
 * and couples the API to the schema.
 */
public final class ProductResponses {
    private ProductResponses() {}

    public record CategoryResponse(Long id, String name, String slug,
                                   String description, Long parentId) {}

    public record ImageResponse(Long id, String url, String altText,
                                int sortOrder, boolean primary) {}

    public record ProductResponse(
            Long id,
            String sku,
            String name,
            String description,
            String brand,
            CategoryResponse category,
            BigDecimal price,
            BigDecimal compareAtPrice,
            String status,
            String imageUrl,
            java.util.Map<String, Object> attributes,
            List<ImageResponse> images,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
            // NOTE: costPrice and deletedAt intentionally absent.
    ) {}

    /** Lighter shape for list/search pages — no description/attributes. */
    public record ProductSummaryResponse(
            Long id,
            String sku,
            String name,
            String brand,
            Long categoryId,
            String categoryName,
            BigDecimal price,
            BigDecimal compareAtPrice,
            String status,
            String imageUrl,
            List<ImageResponse> images
    ) {}

    /** Uniform page envelope so the frontend paginates consistently. */
    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> p) {
            return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(),
                    p.getTotalElements(), p.getTotalPages());
        }
    }
}

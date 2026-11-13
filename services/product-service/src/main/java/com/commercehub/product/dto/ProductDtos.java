package com.commercehub.product.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Inbound DTOs for product write operations.
 *
 * Validation annotations are enforced by @Valid in the controller BEFORE
 * the service runs — controllers stay thin, rules live on the contract.
 * NOTE: no costPrice field here; cost is admin-only and set via a separate
 * privileged endpoint/payload to avoid leaking margin data.
 */
public final class ProductDtos {
    private ProductDtos() {}

    public record ProductRequest(
            @NotBlank(message = "SKU is required")
            @Size(max = 40, message = "SKU max 40 chars")
            String sku,

            @NotBlank(message = "Name is required")
            @Size(max = 200)
            String name,

            String description,

            @Size(max = 120)
            String brand,

            @NotNull(message = "Category is required")
            Long categoryId,

            @NotNull(message = "Price is required")
            @DecimalMin(value = "0.01", message = "Price must be > 0")
            @Digits(integer = 10, fraction = 2, message = "Max 10 integer + 2 fraction digits")
            BigDecimal price,

            @DecimalMin(value = "0.00")
            BigDecimal compareAtPrice,

            String imageUrl,

            Map<String, Object> attributes,

            List<ImageRequest> images
    ) {
        public record ImageRequest(
                @NotBlank String url,
                String altText,
                Integer sortOrder,
                Boolean primary
        ) {}
    }

    /** Partial update — null fields mean "leave unchanged". */
    public record ProductUpdateRequest(
            @Size(max = 200) String name,
            String description,
            @Size(max = 120) String brand,
            @NotNull Long categoryId,
            @DecimalMin(value = "0.01") BigDecimal price,
            BigDecimal compareAtPrice,
            String imageUrl,
            Map<String, Object> attributes,
            List<ProductRequest.ImageRequest> images
    ) {}

    public record StatusChangeRequest(
            @NotNull ProductStatusEnum status
    ) {}

    /** String-based enum mirror so validation errors are readable JSON. */
    public enum ProductStatusEnum { DRAFT, ACTIVE, INACTIVE, DISCONTINUED }
}

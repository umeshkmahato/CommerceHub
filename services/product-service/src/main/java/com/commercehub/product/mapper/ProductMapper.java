package com.commercehub.product.mapper;

import com.commercehub.product.dto.ProductDtos;
import com.commercehub.product.dto.ProductResponses;
import com.commercehub.product.entity.Category;
import com.commercehub.product.entity.Product;
import com.commercehub.product.entity.ProductImage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entity ↔ DTO translation, kept OUT of entities and controllers.
 *
 * Manual mapping (vs MapStruct) is chosen here for transparency — every
 * field decision is visible. The critical rule: costPrice, version, and
 * deletedAt NEVER cross the API boundary.
 */
@Component
public class ProductMapper {

    public ProductResponses.CategoryResponse toCategoryResponse(Category c) {
        if (c == null) return null;
        return new ProductResponses.CategoryResponse(
                c.getId(), c.getName(), c.getSlug(), c.getDescription(),
                c.getParent() != null ? c.getParent().getId() : null);
    }

    public ProductResponses.ImageResponse toImageResponse(ProductImage img) {
        return new ProductResponses.ImageResponse(
                img.getId(), img.getUrl(), img.getAltText(),
                img.getSortOrder(), img.isPrimary());
    }

    public ProductResponses.ProductResponse toResponse(Product p) {
        List<ProductResponses.ImageResponse> images =
                p.getImages() == null ? List.of()
                        : p.getImages().stream().map(this::toImageResponse).toList();
        return new ProductResponses.ProductResponse(
                p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getBrand(),
                toCategoryResponse(p.getCategory()),
                p.getPrice(), p.getCompareAtPrice(),
                p.getStatus().name(), p.getImageUrl(), p.getAttributes(),
                images, p.getCreatedAt(), p.getUpdatedAt());
    }

    public ProductResponses.ProductSummaryResponse toSummary(Product p) {
        List<ProductResponses.ImageResponse> images =
                p.getImages() == null ? List.of()
                        : p.getImages().stream().map(this::toImageResponse).toList();
        Category c = p.getCategory();
        return new ProductResponses.ProductSummaryResponse(
                p.getId(), p.getSku(), p.getName(), p.getBrand(),
                c != null ? c.getId() : null,
                c != null ? c.getName() : null,
                p.getPrice(), p.getCompareAtPrice(),
                p.getStatus().name(), p.getImageUrl(), images);
    }

    /**
     * Apply a create request to a fresh entity. Category is resolved by the
     * service (needs a DB lookup) and passed in.
     */
    public Product toEntity(ProductDtos.ProductRequest req, Category category) {
        Product p = new Product();
        applyRequest(p, req);
        p.setCategory(category);
        p.setStatus(Product.Status.DRAFT);   // new products start as drafts
        return p;
    }

    /** Partial update: only non-null request fields overwrite the entity. */
    public void applyUpdate(Product p, ProductDtos.ProductUpdateRequest req, Category category) {
        if (req.name() != null) p.setName(req.name());
        if (req.description() != null) p.setDescription(req.description());
        if (req.brand() != null) p.setBrand(req.brand());
        if (req.categoryId() != null && category != null) p.setCategory(category);
        if (req.price() != null) p.setPrice(req.price());
        if (req.compareAtPrice() != null) p.setCompareAtPrice(req.compareAtPrice());
        if (req.imageUrl() != null) p.setImageUrl(req.imageUrl());
        if (req.attributes() != null) p.setAttributes(req.attributes());
    }

    private void applyRequest(Product p, ProductDtos.ProductRequest req) {
        p.setSku(req.sku());
        p.setName(req.name());
        p.setDescription(req.description());
        p.setBrand(req.brand());
        p.setPrice(req.price());
        p.setCompareAtPrice(req.compareAtPrice());
        p.setImageUrl(req.imageUrl());
        if (req.attributes() != null) p.setAttributes(req.attributes());
        if (req.images() != null) {
            req.images().forEach(ir -> {
                ProductImage img = new ProductImage();
                img.setProduct(p);
                img.setUrl(ir.url());
                img.setAltText(ir.altText());
                img.setSortOrder(ir.sortOrder() != null ? ir.sortOrder() : 0);
                img.setPrimary(Boolean.TRUE.equals(ir.primary()));
                p.getImages().add(img);
            });
        }
    }
}

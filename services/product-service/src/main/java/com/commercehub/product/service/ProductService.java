package com.commercehub.product.service;

import com.commercehub.product.dto.ProductDtos;
import com.commercehub.product.dto.ProductResponses;
import com.commercehub.product.entity.Category;
import com.commercehub.product.entity.Product;
import com.commercehub.product.entity.ProductImage;
import com.commercehub.product.event.ProductEventPublisher;
import com.commercehub.product.event.ProductEvents.ProductEvent;
import com.commercehub.product.exception.ProductNotFoundException;
import com.commercehub.product.mapper.ProductMapper;
import com.commercehub.product.repository.CategoryRepository;
import com.commercehub.product.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Product business logic — the ONLY layer allowed to touch repositories.
 *
 * Transaction & cache strategy:
 *  - Reads are @Transactional(readOnly = true): tells Hibernate to skip
 *    dirty-checking, enabling flush-mode optimizations and routing to
 *    read replicas if configured.
 *  - Mapper calls happen INSIDE the transaction so lazy collections
 *    (images, category) can initialize without LazyInitializationException.
 *  - @Cacheable on hot reads; @CacheEvict on every mutation. Cache keys are
 *    explicit so different filter combos never collide.
 */
@Service
public class ProductService {

    private final ProductRepository productRepo;
    private final CategoryRepository categoryRepo;
    private final ProductMapper mapper;
    private final ProductEventPublisher events;

    public ProductService(ProductRepository productRepo, CategoryRepository categoryRepo,
                          ProductMapper mapper, ProductEventPublisher events) {
        this.productRepo = productRepo;
        this.categoryRepo = categoryRepo;
        this.mapper = mapper;
        this.events = events;
    }

    // ------------------------------ READS ------------------------------

    @Cacheable(cacheNames = "products", key = "'detail:' + #id")
    @Transactional(readOnly = true)
    public ProductResponses.ProductResponse getById(Long id) {
        Product p = productRepo.findByIdWithImages(id)
                .filter(pr -> !pr.isDeleted())
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
        return mapper.toResponse(p);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponses.ProductSummaryResponse> search(
            String q, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
            int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), parseSort(sort));
        Page<Product> result = productRepo.search(
                blankToNull(q), categoryId, minPrice, maxPrice,
                Product.Status.ACTIVE, pageable);
        // Map inside tx: images/category are entity-graph loaded but session
        // must still be open for any residual lazy access.
        return result.map(mapper::toSummary);
    }

    @Cacheable(cacheNames = "products",
               key = "'page:' + #page + ':' + #size + ':' + #sort")
    @Transactional(readOnly = true)
    public Page<ProductResponses.ProductSummaryResponse> listActive(int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), parseSort(sort));
        return productRepo.findByStatusAndDeletedAtIsNull(Product.Status.ACTIVE, pageable)
                .map(mapper::toSummary);
    }

    @Transactional(readOnly = true)
    public List<ProductResponses.CategoryResponse> listCategories() {
        return categoryRepo.findByParentIsNullOrderByNameAsc().stream()
                .map(mapper::toCategoryResponse).toList();
    }

    // ------------------------------ WRITES (admin) ------------------------------

    @Transactional
    @CacheEvict(cacheNames = "products", allEntries = true)
    public ProductResponses.ProductResponse create(ProductDtos.ProductRequest req) {
        if (productRepo.existsBySku(req.sku())) {
            throw new IllegalArgumentException("SKU already exists: " + req.sku());
        }
        Category category = categoryRepo.findById(req.categoryId())
                .orElseThrow(() -> new ProductNotFoundException("Category not found: " + req.categoryId()));

        Product saved = productRepo.save(mapper.toEntity(req, category));
        events.publish(ProductEvent.of("product.created", saved.getId(),
                saved.getSku(), saved.getName(), saved.getPrice(),
                saved.getCategory().getSlug()));
        return mapper.toResponse(saved);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = "products", key = "'detail:' + #id"),
            @CacheEvict(cacheNames = "products", allEntries = true) // page caches too
    })
    public ProductResponses.ProductResponse update(Long id, ProductDtos.ProductUpdateRequest req) {
        Product p = productRepo.findByIdWithImages(id)
                .filter(pr -> !pr.isDeleted())
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));

        Category category = req.categoryId() != null
                ? categoryRepo.findById(req.categoryId())
                    .orElseThrow(() -> new ProductNotFoundException("Category not found"))
                : null;

        mapper.applyUpdate(p, req, category);
        Product saved = productRepo.save(p);   // @Version guards concurrent edits

        events.publish(ProductEvent.of("product.updated", saved.getId(),
                saved.getSku(), saved.getName(), saved.getPrice(),
                saved.getCategory().getSlug()));
        return mapper.toResponse(saved);
    }

    /** Soft delete — preserves history; status becomes DISCONTINUED. */
    @Transactional
    @CacheEvict(cacheNames = "products", allEntries = true)
    public void deactivate(Long id) {
        Product p = productRepo.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + id));
        p.softDelete();
        productRepo.save(p);
        events.publish(ProductEvent.of("product.deactivated", p.getId(),
                p.getSku(), p.getName(), p.getPrice(), p.getCategory().getSlug()));
    }

    /** Admin listing — all statuses, paginated. */
    @Transactional(readOnly = true)
    public Page<ProductResponses.ProductSummaryResponse> listAll(int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return productRepo.findAllByOrderByCreatedAtDesc(pageable).map(mapper::toSummary);
    }

    // ------------------------------ helpers ------------------------------

    /** Whitelist sort fields — never pass raw client input to Sort.by(). */
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.by(Sort.Direction.ASC, "name");
        return switch (sort) {
            case "price_asc"  -> Sort.by(Sort.Direction.ASC, "price");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "price");
            case "newest"     -> Sort.by(Sort.Direction.DESC, "createdAt");
            default           -> Sort.by(Sort.Direction.ASC, "name");
        };
    }

    private String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s.trim(); }
}

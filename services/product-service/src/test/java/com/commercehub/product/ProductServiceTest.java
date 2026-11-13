package com.commercehub.product;

import com.commercehub.product.dto.ProductDtos;
import com.commercehub.product.dto.ProductResponses;
import com.commercehub.product.entity.Category;
import com.commercehub.product.entity.Product;
import com.commercehub.product.event.ProductEventPublisher;
import com.commercehub.product.exception.ProductNotFoundException;
import com.commercehub.product.mapper.ProductMapper;
import com.commercehub.product.repository.CategoryRepository;
import com.commercehub.product.repository.ProductRepository;
import com.commercehub.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProductService — repositories and Kafka publisher mocked.
 * Verifies business rules WITHOUT a database: this is the fast feedback loop.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock ProductRepository productRepo;
    @Mock CategoryRepository categoryRepo;
    @Mock ProductMapper mapper;
    @Mock ProductEventPublisher events;

    @InjectMocks ProductService service;

    private Product product;
    private Category category;

    @BeforeEach
    void setUp() {
        category = new Category();
        category.setId(1L);
        category.setName("Electronics");
        category.setSlug("electronics");

        product = new Product();
        // id is DB-generated; tests inject it via reflection since the entity
        // intentionally exposes no setId (ids are not client-writable).
        org.springframework.test.util.ReflectionTestUtils.setField(product, "id", 10L);
        product.setSku("SKU-1");
        product.setName("Widget");
        product.setPrice(new BigDecimal("19.99"));
        product.setStatus(Product.Status.ACTIVE);
        product.setCategory(category);
    }

    @Test
    void getById_returnsMappedResponse() {
        ProductResponses.ProductResponse expected = mock(ProductResponses.ProductResponse.class);
        when(productRepo.findByIdWithImages(10L)).thenReturn(Optional.of(product));
        when(mapper.toResponse(product)).thenReturn(expected);

        assertSame(expected, service.getById(10L));
    }

    @Test
    void getById_deletedProduct_throwsNotFound() {
        product.softDelete();   // public API for soft delete — no setter needed
        when(productRepo.findByIdWithImages(10L)).thenReturn(Optional.of(product));

        assertThrows(ProductNotFoundException.class, () -> service.getById(10L));
    }

    @Test
    void create_duplicateSku_rejectedBeforeSave() {
        ProductDtos.ProductRequest req = new ProductDtos.ProductRequest(
                "SKU-1", "Widget", null, null, 1L,
                new BigDecimal("9.99"), null, null, null, null);

        when(productRepo.existsBySku("SKU-1")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.create(req));
        verify(productRepo, never()).save(any());
    }

    @Test
    void create_validRequest_savesAndPublishesEvent() {
        ProductDtos.ProductRequest req = new ProductDtos.ProductRequest(
                "SKU-NEW", "Gadget", "desc", "Acme", 1L,
                new BigDecimal("29.99"), null, null, null, null);

        Product saved = new Product();
        org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 11L);
        saved.setSku("SKU-NEW");
        saved.setName("Gadget");
        saved.setPrice(new BigDecimal("29.99"));
        saved.setCategory(category);

        when(productRepo.existsBySku("SKU-NEW")).thenReturn(false);
        when(categoryRepo.findById(1L)).thenReturn(Optional.of(category));
        when(mapper.toEntity(req, category)).thenReturn(saved);
        when(productRepo.save(saved)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(mock(ProductResponses.ProductResponse.class));

        service.create(req);

        verify(events).publish(argThat(e -> e.eventType().equals("product.created")
                && e.productId().equals(11L)));
    }

    @Test
    void deactivate_softDeletesAndPublishes() {
        when(productRepo.findById(10L)).thenReturn(Optional.of(product));
        when(productRepo.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(10L);

        assertNotNull(product.getDeletedAt());
        assertEquals(Product.Status.DISCONTINUED, product.getStatus());
        verify(events).publish(argThat(e -> e.eventType().equals("product.deactivated")));
    }

    @Test
    void search_mapsPageToSummaries() {
        when(productRepo.search(isNull(), isNull(), isNull(), isNull(),
                eq(Product.Status.ACTIVE), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));
        when(mapper.toSummary(product)).thenReturn(mock(ProductResponses.ProductSummaryResponse.class));

        var page = service.search(null, null, null, null, 0, 20, "name");
        assertEquals(1, page.getTotalElements());
    }
}

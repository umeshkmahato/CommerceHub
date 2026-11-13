package com.commercehub.product;

import com.commercehub.product.entity.Category;
import com.commercehub.product.entity.Product;
import com.commercehub.product.repository.CategoryRepository;
import com.commercehub.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository integration test against a REAL MySQL 8.4 container.
 *
 * Why Testcontainers and not H2: MySQL-specific features we depend on —
 * generated columns, FULLTEXT indexes, CHECK constraints, ON UPDATE
 * timestamps — silently behave differently or not at all on H2. Testing
 * against the production engine catches schema bugs before deploy.
 *
 * Requires Docker running locally (`docker info` to verify).
 */
@Testcontainers
@SpringBootTest
class ProductRepositoryIT {

    @Container
    @ServiceConnection   // auto-wires JDBC URL/credentials into Spring
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("productdb")
            .withUsername("root")
            .withPassword("root");

    @Autowired ProductRepository productRepo;
    @Autowired CategoryRepository categoryRepo;

    @Test
    void flywayMigratesAndCategoryRoundTrips() {
        Category c = new Category();
        c.setName("Electronics");
        c.setSlug("electronics-it");
        Category saved = categoryRepo.save(c);

        Optional<Category> found = categoryRepo.findBySlug("electronics-it");
        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
    }

    @Test
    void productPersistsWithMoneyAsBigDecimal() {
        Category c = categoryRepo.findBySlug("electronics-it").orElseGet(() -> {
            Category nc = new Category();
            nc.setName("Tools"); nc.setSlug("tools-it");
            return categoryRepo.save(nc);
        });

        Product p = new Product();
        p.setSku("IT-SKU-1");
        p.setName("Integration Widget");
        p.setPrice(new BigDecimal("123.45"));
        p.setStatus(Product.Status.ACTIVE);
        p.setCategory(c);
        productRepo.save(p);

        Optional<Product> found = productRepo.findById(p.getId());
        assertTrue(found.isPresent());
        assertEquals(0, new BigDecimal("123.45").compareTo(found.get().getPrice()));
        assertNotNull(found.get().getVersion());   // @Version initialized
    }

    @Test
    void duplicateSku_violatesUniqueConstraint() {
        Category c = categoryRepo.findAll().get(0);
        Product p1 = new Product();
        p1.setSku("DUP-SKU"); p1.setName("A");
        p1.setPrice(BigDecimal.TEN); p1.setCategory(c);
        productRepo.save(p1);

        Product p2 = new Product();
        p2.setSku("DUP-SKU"); p2.setName("B");
        p2.setPrice(BigDecimal.TEN); p2.setCategory(c);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> productRepo.saveAndFlush(p2));
    }
}

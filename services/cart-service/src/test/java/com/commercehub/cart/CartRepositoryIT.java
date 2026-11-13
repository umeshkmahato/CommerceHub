package com.commercehub.cart;

import com.commercehub.cart.entity.Cart;
import com.commercehub.cart.entity.CartItem;
import com.commercehub.cart.repository.CartItemRepository;
import com.commercehub.cart.repository.CartRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Cart repository IT — real MySQL 8.4: unique one-cart-per-customer,
 *  price snapshots, item upsert lookup. */
@Testcontainers
@SpringBootTest
class CartRepositoryIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("cartdb").withUsername("root").withPassword("root");

    @Autowired CartRepository cartRepo;
    @Autowired CartItemRepository itemRepo;

    @Test
    void oneCartPerCustomer_uniqueConstraintEnforced() {
        Cart c1 = new Cart(); c1.setCustomerId(1001L);
        cartRepo.saveAndFlush(c1);

        Cart c2 = new Cart(); c2.setCustomerId(1001L);
        assertThrows(DataIntegrityViolationException.class,
                () -> cartRepo.saveAndFlush(c2));
    }

    @Test
    void cartItem_persistsSnapshotAndFindsByCartAndProduct() {
        Cart cart = new Cart(); cart.setCustomerId(2002L);
        cart = cartRepo.save(cart);

        CartItem item = new CartItem();
        item.setCart(cart); item.setProductId(55L); item.setSku("SKU-55");
        item.setProductName("Widget"); item.setUnitPrice(new BigDecimal("19.99"));
        item.setQuantity(2);
        itemRepo.save(item);

        var found = itemRepo.findByCartIdAndProductId(cart.getId(), 55L);
        assertTrue(found.isPresent());
        assertEquals(0, new BigDecimal("19.99").compareTo(found.get().getUnitPrice()));
        assertEquals(2, found.get().getQuantity());
    }

    @Test
    void findByCustomerId_returnsEmptyForUnknown() {
        assertTrue(cartRepo.findByCustomerId(999999L).isEmpty());
    }
}

package com.commercehub.cart;

import com.commercehub.cart.client.ProductClient;
import com.commercehub.cart.dto.CartDtos;
import com.commercehub.cart.entity.Cart;
import com.commercehub.cart.entity.CartItem;
import com.commercehub.cart.repository.CartItemRepository;
import com.commercehub.cart.repository.CartRepository;
import com.commercehub.cart.service.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CartService unit tests — product client mocked, repos mocked.
 * Covers: validation gating, price snapshot, upsert semantics, totals.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock CartRepository cartRepo;
    @Mock CartItemRepository itemRepo;
    @Mock ProductClient productClient;

    @InjectMocks CartService cartService;

    private Cart cart;

    @BeforeEach
    void setUp() {
        cart = new Cart();
        org.springframework.test.util.ReflectionTestUtils.setField(cart, "id", 5L);
        cart.setCustomerId(42L);
    }

    private ProductClient.ProductInfo activeProduct() {
        return new ProductClient.ProductInfo(100L, "SKU-1", "Widget",
                new BigDecimal("19.99"), "ACTIVE");
    }

    @Test
    void addItem_inactiveProduct_rejected() {
        when(productClient.getProduct(100L)).thenReturn(
                new ProductClient.ProductInfo(100L, "SKU-1", "Widget",
                        BigDecimal.TEN, "DISCONTINUED"));

        assertThrows(IllegalArgumentException.class, () ->
                cartService.addItem(42L, new CartDtos.AddItemRequest(100L, 1)));
        verify(itemRepo, never()).save(any());
    }

    @Test
    void addItem_newProduct_snapshotsPrice() {
        when(productClient.getProduct(100L)).thenReturn(activeProduct());
        when(cartRepo.findByCustomerId(42L)).thenReturn(Optional.of(cart));
        when(itemRepo.findByCartIdAndProductId(5L, 100L)).thenReturn(Optional.empty());
        when(cartRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CartDtos.CartResponse resp = cartService.addItem(42L,
                new CartDtos.AddItemRequest(100L, 2));

        assertEquals(1, resp.items().size());
        assertEquals(new BigDecimal("19.99"), resp.items().get(0).unitPrice());
        assertEquals(2, resp.items().get(0).quantity());
        assertEquals(new BigDecimal("39.98"), resp.subtotal());
    }

    @Test
    void addItem_existingProduct_accumulatesQuantity() {
        when(productClient.getProduct(100L)).thenReturn(activeProduct());
        when(cartRepo.findByCustomerId(42L)).thenReturn(Optional.of(cart));

        CartItem existing = new CartItem();
        existing.setCart(cart);
        existing.setProductId(100L);
        existing.setQuantity(3);
        existing.setUnitPrice(new BigDecimal("19.99"));
        // JPA would have this item in cart.items via lazy load — mirror that.
        cart.getItems().add(existing);
        when(itemRepo.findByCartIdAndProductId(5L, 100L)).thenReturn(Optional.of(existing));
        when(cartRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CartDtos.CartResponse resp = cartService.addItem(42L,
                new CartDtos.AddItemRequest(100L, 2));

        assertEquals(5, resp.items().get(0).quantity());   // 3 + 2
    }

    @Test
    void updateQuantity_zero_removesItem() {
        when(cartRepo.findByCustomerId(42L)).thenReturn(Optional.of(cart));
        CartItem item = new CartItem();
        item.setCart(cart); item.setProductId(100L); item.setQuantity(2);
        when(itemRepo.findByCartIdAndProductId(5L, 100L)).thenReturn(Optional.of(item));

        cartService.updateQuantity(42L, 100L, 0);

        verify(itemRepo).delete(item);
    }

    @Test
    void clearCart_marksConvertedAndEmpties() {
        when(cartRepo.findByCustomerId(42L)).thenReturn(Optional.of(cart));
        CartItem item = new CartItem();
        item.setCart(cart); item.setProductId(1L);
        cart.getItems().add(item);
        when(cartRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        cartService.clearCart(42L);

        assertEquals(Cart.Status.CONVERTED, cart.getStatus());
        assertTrue(cart.getItems().isEmpty());
    }
}

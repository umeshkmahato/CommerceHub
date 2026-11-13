package com.commercehub.cart.service;

import com.commercehub.cart.client.ProductClient;
import com.commercehub.cart.dto.CartDtos;
import com.commercehub.cart.entity.Cart;
import com.commercehub.cart.entity.CartItem;
import com.commercehub.cart.repository.CartItemRepository;
import com.commercehub.cart.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Cart business logic.
 *
 * Concurrency note: Cart has @Version (optimistic lock). Two simultaneous
 * add-to-cart requests on the same cart → one wins, loser gets
 * ObjectOptimisticLockingFailureException → retried by client. Acceptable
 * for cart UX; inventory-critical paths use pessimistic locks instead.
 */
@Service
public class CartService {

    private final CartRepository cartRepo;
    private final CartItemRepository itemRepo;
    private final ProductClient productClient;

    public CartService(CartRepository cartRepo, CartItemRepository itemRepo,
                       ProductClient productClient) {
        this.cartRepo = cartRepo;
        this.itemRepo = itemRepo;
        this.productClient = productClient;
    }

    // ------------------------------ reads ------------------------------

    @Transactional
    public CartDtos.CartResponse getCart(Long customerId) {
        Cart cart = getOrCreateCart(customerId);
        return toResponse(cart);
    }

    // ------------------------------ mutations ------------------------------

    @Transactional
    public CartDtos.CartResponse addItem(Long customerId, CartDtos.AddItemRequest req) {
        // 1. Validate against product-service: exists AND ACTIVE.
        ProductClient.ProductInfo product = productClient.getProduct(req.productId());
        if (product == null || !"ACTIVE".equals(product.status())) {
            throw new IllegalArgumentException("Product unavailable: " + req.productId());
        }

        Cart cart = getOrCreateCart(customerId);

        // 2. Upsert: product appears once per cart (uk_cart_items_cart_product).
        CartItem item = itemRepo.findByCartIdAndProductId(cart.getId(), req.productId())
                .orElseGet(() -> {
                    CartItem ni = new CartItem();
                    ni.setCart(cart);
                    ni.setProductId(product.id());
                    ni.setSku(product.sku());
                    ni.setProductName(product.name());
                    ni.setUnitPrice(product.price());   // snapshot NOW
                    cart.getItems().add(ni);
                    return ni;
                });
        item.setQuantity(item.getQuantity() + req.quantity());
        itemRepo.save(item);

        return toResponse(cartRepo.save(cart));
    }

    @Transactional
    public CartDtos.CartResponse updateQuantity(Long customerId, Long productId, int quantity) {
        Cart cart = requireCart(customerId);
        CartItem item = itemRepo.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new IllegalArgumentException("Item not in cart"));
        if (quantity <= 0) {
            itemRepo.delete(item);          // qty 0 = remove
        } else {
            item.setQuantity(quantity);
            itemRepo.save(item);
        }
        return toResponse(cart);
    }

    @Transactional
    public CartDtos.CartResponse removeItem(Long customerId, Long productId) {
        Cart cart = requireCart(customerId);
        itemRepo.deleteByCartIdAndProductId(cart.getId(), productId);
        cart.getItems().removeIf(i -> i.getProductId().equals(productId));
        return toResponse(cartRepo.save(cart));
    }

    /** Called by order-service via internal endpoint after order creation. */
    @Transactional
    public void clearCart(Long customerId) {
        cartRepo.findByCustomerId(customerId).ifPresent(cart -> {
            cart.markConverted();
            cart.getItems().clear();       // orphanRemoval deletes rows
            cartRepo.save(cart);
        });
    }

    // ------------------------------ internals ------------------------------

    /** Get existing ACTIVE cart or create one — safe under the unique key. */
    private Cart getOrCreateCart(Long customerId) {
        return cartRepo.findByCustomerId(customerId)
                .filter(c -> c.getStatus() == Cart.Status.ACTIVE)
                .orElseGet(() -> {
                    Cart c = new Cart();
                    c.setCustomerId(customerId);
                    return cartRepo.save(c);
                });
    }

    private Cart requireCart(Long customerId) {
        return cartRepo.findByCustomerId(customerId)
                .filter(c -> c.getStatus() == Cart.Status.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("No active cart"));
    }

    private CartDtos.CartResponse toResponse(Cart cart) {
        List<CartItem> sorted = cart.getItems().stream()
                .sorted(Comparator.comparing(CartItem::getId))
                .toList();
        List<CartDtos.CartItemResponse> items = sorted.stream()
                .map(i -> new CartDtos.CartItemResponse(i.getId(), i.getProductId(),
                        i.getSku(), i.getProductName(), i.getUnitPrice(),
                        i.getQuantity(), i.lineTotal()))
                .toList();
        BigDecimal subtotal = items.stream()
                .map(CartDtos.CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartDtos.CartResponse(cart.getId(), cart.getStatus().name(),
                items, subtotal, items.stream().mapToInt(CartDtos.CartItemResponse::quantity).sum());
    }
}

package com.commercehub.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cart Service — ephemeral shopping-cart state.
 *
 * Owns cartdb (MySQL). Calls product-service synchronously (RestClient)
 * to validate products and snapshot prices at add-to-cart time.
 * The AUTHORITATIVE price is re-validated by order-service at checkout —
 * the cart snapshot is a UX convenience, not a pricing contract.
 */
@SpringBootApplication
@EnableScheduling   // for abandoned-cart expiry sweeper (later part)
public class CartServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}

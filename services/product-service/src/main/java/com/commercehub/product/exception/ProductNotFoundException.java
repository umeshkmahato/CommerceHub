package com.commercehub.product.exception;

/** Thrown when a requested product/category does not exist (or is deleted). */
public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String message) { super(message); }
}

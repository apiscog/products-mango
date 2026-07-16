package com.mango.products.application.exception;

public final class ProductNotFoundException extends RuntimeException {

	public ProductNotFoundException(long productId) {
		super("Product " + productId + " was not found");
	}

}

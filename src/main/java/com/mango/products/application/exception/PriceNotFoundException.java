package com.mango.products.application.exception;

import java.time.LocalDate;

public final class PriceNotFoundException extends RuntimeException {

	public PriceNotFoundException(long productId, LocalDate date) {
		super("Product " + productId + " has no price for date " + date);
	}

}

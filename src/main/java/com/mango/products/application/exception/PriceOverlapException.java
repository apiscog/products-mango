package com.mango.products.application.exception;

import java.time.LocalDate;

public final class PriceOverlapException extends RuntimeException {

	public PriceOverlapException(long productId, LocalDate initDate, LocalDate endDate) {
		super("Price period " + initDate + " to " + endDate + " overlaps an existing price for product " + productId);
	}

}

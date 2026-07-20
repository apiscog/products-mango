package com.mango.products.application.exception;

public class ExchangeRateUnavailableException extends RuntimeException {

	public ExchangeRateUnavailableException() {
		super("Currency conversion is temporarily unavailable");
	}

	public ExchangeRateUnavailableException(Throwable cause) {
		super("Currency conversion is temporarily unavailable", cause);
	}
}

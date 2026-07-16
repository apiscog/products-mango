package com.mango.products.domain.exception;

public final class DomainValidationException extends IllegalArgumentException {

	public DomainValidationException(String message) {
		super(message);
	}

}

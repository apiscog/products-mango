package com.mango.products.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.mango.products.domain.exception.DomainValidationException;

public enum CurrencyCode {

	EUR,
	USD,
	GBP,
	JPY,
	CHF;

	private static final String SUPPORTED = Arrays.stream(values())
			.map(Enum::name)
			.collect(Collectors.joining(", "));

	public static CurrencyCode from(String value) {
		if (value == null) {
			throw unsupported("null");
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (normalized.length() != 3) {
			throw unsupported(value);
		}
		try {
			return valueOf(normalized);
		}
		catch (IllegalArgumentException exception) {
			throw unsupported(value);
		}
	}

	public String lowerCase() {
		return name().toLowerCase(Locale.ROOT);
	}

	private static DomainValidationException unsupported(String value) {
		return new DomainValidationException(
				"Unsupported currency '" + value + "'. Supported currencies: " + SUPPORTED);
	}
}

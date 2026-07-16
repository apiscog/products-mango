package com.mango.products.domain.model;

import com.mango.products.domain.exception.DomainValidationException;

public final class Product {

	private static final int MAX_NAME_LENGTH = 120;
	private static final int MAX_DESCRIPTION_LENGTH = 1000;

	private final Long id;
	private final String name;
	private final String description;

	private Product(Long id, String name, String description) {
		this.id = id;
		this.name = validateName(name);
		this.description = validateDescription(description);
	}

	public static Product create(String name, String description) {
		return new Product(null, name, description);
	}

	public static Product reconstitute(Long id, String name, String description) {
		if (id == null) {
			throw new DomainValidationException("Product id is required when reconstituting a persisted product");
		}
		return new Product(id, name, description);
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	private static String validateName(String name) {
		if (name == null) {
			throw new DomainValidationException("Product name is required");
		}
		if (name.isBlank()) {
			throw new DomainValidationException("Product name must not be blank");
		}
		if (length(name) > MAX_NAME_LENGTH) {
			throw new DomainValidationException("Product name must not exceed 120 characters");
		}
		return name;
	}

	private static String validateDescription(String description) {
		if (description != null && length(description) > MAX_DESCRIPTION_LENGTH) {
			throw new DomainValidationException("Product description must not exceed 1000 characters");
		}
		return description;
	}

	private static int length(String value) {
		return value.codePointCount(0, value.length());
	}

}

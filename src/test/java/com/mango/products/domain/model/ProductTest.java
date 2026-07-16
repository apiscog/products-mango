package com.mango.products.domain.model;

import org.junit.jupiter.api.Test;

import com.mango.products.domain.exception.DomainValidationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {

	@Test
	void createsValidNewProductAndPreservesReceivedText() {
		Product product = Product.create("  Running shoes  ", "Limited edition");

		assertNull(product.getId());
		assertEquals("  Running shoes  ", product.getName());
		assertEquals("Limited edition", product.getDescription());
	}

	@Test
	void rejectsNullName() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Product.create(null, "Description"));

		assertEquals("Product name is required", exception.getMessage());
	}

	@Test
	void rejectsEmptyName() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Product.create("", "Description"));

		assertEquals("Product name must not be blank", exception.getMessage());
	}

	@Test
	void rejectsWhitespaceOnlyName() {
		assertThrows(
				DomainValidationException.class,
				() -> Product.create(" \t\n", "Description"));
	}

	@Test
	void rejectsNameLongerThan120Characters() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Product.create("n".repeat(121), "Description"));

		assertEquals("Product name must not exceed 120 characters", exception.getMessage());
	}

	@Test
	void acceptsNullDescription() {
		Product product = Product.create("Product", null);

		assertNull(product.getDescription());
	}

	@Test
	void acceptsDescriptionWithExactly1000Characters() {
		String description = "d".repeat(1000);

		Product product = Product.create("Product", description);

		assertEquals(description, product.getDescription());
	}

	@Test
	void rejectsDescriptionLongerThan1000Characters() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Product.create("Product", "d".repeat(1001)));

		assertEquals("Product description must not exceed 1000 characters", exception.getMessage());
	}

	@Test
	void reconstitutesPersistedProductWithId() {
		Product product = Product.reconstitute(42L, "Product", "Description");

		assertEquals(42L, product.getId());
		assertEquals("Product", product.getName());
		assertEquals("Description", product.getDescription());
	}

	@Test
	void rejectsReconstitutionWithoutId() {
		assertThrows(
				DomainValidationException.class,
				() -> Product.reconstitute(null, "Product", "Description"));
	}

}

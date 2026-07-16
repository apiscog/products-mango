package com.mango.products.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mango.products.PostgreSQLIntegrationTestBase;
import com.mango.products.adapter.out.persistence.adapter.ProductPersistenceAdapter;
import com.mango.products.domain.model.Product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPersistenceAdapterIT extends PostgreSQLIntegrationTestBase {

	@Autowired
	private ProductPersistenceAdapter adapter;

	@Test
	void savesNewProductAssignsIdAndPreservesFields() {
		Product saved = adapter.save(Product.create("  Running shoes  ", "Limited edition"));

		assertNotNull(saved.getId());
		assertEquals("  Running shoes  ", saved.getName());
		assertEquals("Limited edition", saved.getDescription());
	}

	@Test
	void findsAndReconstitutesExistingProduct() {
		Product saved = adapter.save(Product.create("Product", null));

		Product found = adapter.findById(saved.getId()).orElseThrow();

		assertEquals(saved.getId(), found.getId());
		assertEquals("Product", found.getName());
		assertNull(found.getDescription());
	}

	@Test
	void returnsEmptyWhenProductDoesNotExist() {
		assertTrue(adapter.findById(Long.MAX_VALUE).isEmpty());
	}

	@Test
	void reportsWhetherProductExists() {
		Product saved = adapter.save(Product.create("Product", "Description"));

		assertTrue(adapter.existsById(saved.getId()));
		assertFalse(adapter.existsById(Long.MAX_VALUE));
	}

}

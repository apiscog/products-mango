package com.mango.products;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductsApplicationTest {

	@Test
	void applicationUsesExpectedBasePackage() {
		assertEquals("com.mango.products", ProductsApplication.class.getPackageName());
	}

}

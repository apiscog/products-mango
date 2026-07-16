package com.mango.products.application.port.out;

import java.util.Optional;

import com.mango.products.domain.model.Product;

public interface ProductRepository {

	Product save(Product product);

	Optional<Product> findById(long productId);

	boolean existsById(long productId);

}

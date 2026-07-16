package com.mango.products.adapter.out.persistence.adapter;

import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.mango.products.adapter.out.persistence.mapper.ProductPersistenceMapper;
import com.mango.products.adapter.out.persistence.repository.SpringDataProductRepository;
import com.mango.products.application.port.out.ProductRepository;
import com.mango.products.domain.model.Product;

@Repository
public class ProductPersistenceAdapter implements ProductRepository {

	private final SpringDataProductRepository repository;

	public ProductPersistenceAdapter(SpringDataProductRepository repository) {
		this.repository = repository;
	}

	@Override
	public Product save(Product product) {
		return ProductPersistenceMapper.toDomain(repository.save(ProductPersistenceMapper.toEntity(product)));
	}

	@Override
	public Optional<Product> findById(long productId) {
		return repository.findById(productId).map(ProductPersistenceMapper::toDomain);
	}

	@Override
	public boolean existsById(long productId) {
		return repository.existsById(productId);
	}

}

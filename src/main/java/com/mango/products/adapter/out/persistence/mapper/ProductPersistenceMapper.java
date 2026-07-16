package com.mango.products.adapter.out.persistence.mapper;

import com.mango.products.adapter.out.persistence.entity.ProductJpaEntity;
import com.mango.products.domain.model.Product;

public final class ProductPersistenceMapper {

	private ProductPersistenceMapper() {
	}

	public static ProductJpaEntity toEntity(Product product) {
		return new ProductJpaEntity(product.getId(), product.getName(), product.getDescription());
	}

	public static Product toDomain(ProductJpaEntity entity) {
		return Product.reconstitute(entity.getId(), entity.getName(), entity.getDescription());
	}

}

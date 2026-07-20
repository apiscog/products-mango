package com.mango.products.adapter.out.persistence.mapper;

import com.mango.products.adapter.out.persistence.entity.PriceJpaEntity;
import com.mango.products.domain.model.Price;

public final class PricePersistenceMapper {

	private PricePersistenceMapper() {
	}

	public static PriceJpaEntity toEntity(Price price) {
		return new PriceJpaEntity(
				price.getId(),
				price.getProductId(),
				price.getValue(),
				price.getCurrency(),
				price.getInitDate(),
				price.getEndDate());
	}

	public static Price toDomain(PriceJpaEntity entity) {
		return Price.reconstitute(
				entity.getId(),
				entity.getProductId(),
				entity.getValue(),
				entity.getCurrency(),
				entity.getInitDate(),
				entity.getEndDate());
	}

}

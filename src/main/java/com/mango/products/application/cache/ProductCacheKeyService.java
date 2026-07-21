package com.mango.products.application.cache;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.mango.products.domain.exception.DomainValidationException;

@Component
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class ProductCacheKeyService {

	private final ProductCacheVersionStore versionStore;

	public ProductCacheKeyService(ProductCacheVersionStore versionStore) {
		this.versionStore = versionStore;
	}

	public String currentPriceKey(long productId, LocalDate date) {
		validateProductId(productId);
		if (date == null) {
			throw new DomainValidationException("Price date is required");
		}
		return productId + "::v" + versionStore.getVersion(productId) + "::" + date;
	}

	public String priceHistoryKey(long productId) {
		validateProductId(productId);
		return productId + "::v" + versionStore.getVersion(productId);
	}

	private static void validateProductId(long productId) {
		if (productId <= 0) {
			throw new DomainValidationException("Product id must be greater than zero");
		}
	}

}

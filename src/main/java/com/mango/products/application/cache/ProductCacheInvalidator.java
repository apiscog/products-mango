package com.mango.products.application.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class ProductCacheInvalidator {

	private final ProductCacheVersionStore versionStore;

	public ProductCacheInvalidator(ProductCacheVersionStore versionStore) {
		this.versionStore = versionStore;
	}

	public void invalidateProduct(long productId) {
		versionStore.incrementVersion(productId);
	}

}

package com.mango.products.application.cache;

public interface ProductCacheVersionStore {

	long getVersion(long productId);

	void incrementVersion(long productId);

}

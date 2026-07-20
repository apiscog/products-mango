package com.mango.products.adapter.out.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

public class FailOpenCacheErrorHandler implements CacheErrorHandler {

	private final RedisCacheFailureReporter failureReporter;

	public FailOpenCacheErrorHandler(RedisCacheFailureReporter failureReporter) {
		this.failureReporter = failureReporter;
	}

	@Override
	public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
		failureReporter.report("get", cache.getName(), key, exception);
	}

	@Override
	public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
		failureReporter.report("put", cache.getName(), key, exception);
	}

	@Override
	public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
		failureReporter.report("evict", cache.getName(), key, exception);
	}

	@Override
	public void handleCacheClearError(RuntimeException exception, Cache cache) {
		failureReporter.report("clear", cache.getName(), "*", exception);
	}

}

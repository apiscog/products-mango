package com.mango.products.adapter.out.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;

class FailOpenCacheErrorHandlerTest {

	@Test
	void ignoresCacheFailuresSoTheInvocationCanContinue() {
		RedisCacheFailureReporter reporter = mock(RedisCacheFailureReporter.class);
		Cache cache = mock(Cache.class);
		RuntimeException failure = new RuntimeException("Redis unavailable");
		when(cache.getName()).thenReturn("current-price");
		FailOpenCacheErrorHandler handler = new FailOpenCacheErrorHandler(reporter);

		assertDoesNotThrow(() -> handler.handleCacheGetError(failure, cache, "1::v0::2024-04-15"));
		assertDoesNotThrow(() -> handler.handleCachePutError(failure, cache, "key", "value"));
		assertDoesNotThrow(() -> handler.handleCacheEvictError(failure, cache, "key"));
		assertDoesNotThrow(() -> handler.handleCacheClearError(failure, cache));

		verify(reporter).report("get", "current-price", "1::v0::2024-04-15", failure);
		verify(reporter).report("put", "current-price", "key", failure);
		verify(reporter).report("evict", "current-price", "key", failure);
		verify(reporter).report("clear", "current-price", "*", failure);
	}

}

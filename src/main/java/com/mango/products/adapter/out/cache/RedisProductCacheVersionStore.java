package com.mango.products.adapter.out.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.mango.products.application.cache.ProductCacheVersionStore;

@Repository
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisProductCacheVersionStore implements ProductCacheVersionStore {

	private static final String VERSION_KEY_PREFIX = "products::cache-version::";

	private final StringRedisTemplate redisTemplate;
	private final RedisCacheFailureReporter failureReporter;

	public RedisProductCacheVersionStore(
			StringRedisTemplate redisTemplate,
			RedisCacheFailureReporter failureReporter) {
		this.redisTemplate = redisTemplate;
		this.failureReporter = failureReporter;
	}

	@Override
	public long getVersion(long productId) {
		String key = versionKey(productId);
		try {
			String value = redisTemplate.opsForValue().get(key);
			return value == null ? 0L : Long.parseLong(value);
		}
		catch (RuntimeException exception) {
			failureReporter.report("version-get", "cache-version", key, exception);
			return 0L;
		}
	}

	@Override
	public void incrementVersion(long productId) {
		String key = versionKey(productId);
		try {
			redisTemplate.opsForValue().increment(key);
		}
		catch (RuntimeException exception) {
			failureReporter.report("version-increment", "cache-version", key, exception);
		}
	}

	public static String versionKey(long productId) {
		return VERSION_KEY_PREFIX + productId;
	}

}

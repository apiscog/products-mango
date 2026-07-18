package com.mango.products.adapter.out.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class RedisCacheFailureReporter {

	private static final Logger LOGGER = LoggerFactory.getLogger(RedisCacheFailureReporter.class);
	private static final long LOG_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();

	private final MeterRegistry meterRegistry;
	private final AtomicLong nextLogNanos = new AtomicLong();

	public RedisCacheFailureReporter(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void report(String operation, String cacheName, Object key, RuntimeException exception) {
		Counter.builder("products.cache.errors")
				.tag("operation", operation)
				.tag("cache", cacheName)
				.register(meterRegistry)
				.increment();

		long now = System.nanoTime();
		long next = nextLogNanos.get();
		if (now >= next && nextLogNanos.compareAndSet(next, now + LOG_INTERVAL_NANOS)) {
			LOGGER.warn(
					"Redis cache {} failed for cache '{}' and key '{}'; continuing with PostgreSQL",
					operation,
					cacheName,
					key,
					exception);
		}
	}

}

package com.mango.products;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public abstract class RedisIntegrationTestBase extends PostgreSQLIntegrationTestBase {

	protected static final String REDIS_IMAGE = "redis:7.4.9-alpine";
	private static final int REDIS_PORT = 6379;

	protected static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
			.withExposedPorts(REDIS_PORT)
			.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

	static {
		REDIS.start();
	}

	@Autowired
	protected StringRedisTemplate redisTemplate;

	@DynamicPropertySource
	static void configureRedis(DynamicPropertyRegistry registry) {
		registry.add("spring.cache.type", () -> "redis");
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
		registry.add("spring.data.redis.connect-timeout", () -> "500ms");
		registry.add("spring.data.redis.timeout", () -> "1s");
	}

	@BeforeEach
	void cleanRedis() {
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
	}

}

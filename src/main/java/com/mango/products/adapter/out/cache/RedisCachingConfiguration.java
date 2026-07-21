package com.mango.products.adapter.out.cache;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mango.products.application.cache.ProductCacheNames;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;

@Configuration
@EnableCaching
@EnableConfigurationProperties(ProductCacheProperties.class)
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisCachingConfiguration implements CachingConfigurer {

	private final RedisConnectionFactory connectionFactory;
	private final ProductCacheProperties properties;
	private final RedisCacheFailureReporter failureReporter;

	public RedisCachingConfiguration(
			RedisConnectionFactory connectionFactory,
			ProductCacheProperties properties,
			RedisCacheFailureReporter failureReporter) {
		this.connectionFactory = connectionFactory;
		this.properties = properties;
		this.failureReporter = failureReporter;
	}

	@Bean
	@Override
	public CacheManager cacheManager() {
		ObjectMapper redisObjectMapper = JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.build();

		RedisCacheConfiguration baseConfiguration = RedisCacheConfiguration.defaultCacheConfig()
				.disableCachingNullValues()
				.computePrefixWith(cacheName -> "products::" + cacheName + "::")
				.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(
						new StringRedisSerializer()));

		Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
				ProductCacheNames.CURRENT_PRICE,
				baseConfiguration
						.entryTtl(properties.currentPriceTtl())
						.serializeValuesWith(jsonPair(redisObjectMapper, CurrentPriceResult.class)),
				ProductCacheNames.PRICE_HISTORY,
				baseConfiguration
						.entryTtl(properties.priceHistoryTtl())
						.serializeValuesWith(jsonPair(redisObjectMapper, ProductHistoryResult.class))
		);

		return RedisCacheManager.builder(connectionFactory)
				.cacheDefaults(baseConfiguration)
				.withInitialCacheConfigurations(cacheConfigurations)
				.disableCreateOnMissingCache()
				.enableStatistics()
				.build();
	}

	@Bean
	@Override
	public CacheErrorHandler errorHandler() {
		return new FailOpenCacheErrorHandler(failureReporter);
	}

	private static <T> RedisSerializationContext.SerializationPair<T> jsonPair(
			ObjectMapper objectMapper,
			Class<T> valueType) {
		Jackson2JsonRedisSerializer<T> serializer =
				new Jackson2JsonRedisSerializer<>(objectMapper, valueType);
		return RedisSerializationContext.SerializationPair.fromSerializer(serializer);
	}

}

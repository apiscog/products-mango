package com.mango.products.adapter.out.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisCallback;

import com.mango.products.RedisIntegrationTestBase;
import com.mango.products.application.port.in.ProductUseCases;
import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;

@SpringBootTest
class RedisCacheIT extends RedisIntegrationTestBase {

	private static final LocalDate FIRST_DATE = LocalDate.of(2024, 4, 15);

	@Autowired
	private ProductUseCases useCases;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void currentPriceIsStoredAsJsonAndSecondCallDoesNotNeedPostgreSQL() {
		long productId = createProductWithPrice("Cached price", "99.99", "2024-01-01", "2024-06-30");

		CurrentPriceResult first = useCases.getPriceAtDate(productId, FIRST_DATE);
		String key = currentPriceKey(productId, 1, FIRST_DATE);
		String rawJson = redisTemplate.opsForValue().get(key);

		assertEquals(new BigDecimal("99.99"), first.value());
		assertNotNull(rawJson);
		assertTrue(rawJson.contains("value"));
		assertTrue(rawJson.contains("99.99"));
		assertTtl(key, 300);

		jdbcTemplate.update("DELETE FROM prices WHERE product_id = ?", productId);
		assertEquals(first, useCases.getPriceAtDate(productId, FIRST_DATE));
	}

	@Test
	void differentDatesCreateDifferentReadableKeys() {
		long productId = createProduct("Dates");
		addPrice(productId, "10.00", "2024-01-01", "2024-06-30");
		addPrice(productId, "20.00", "2024-07-01", null);

		useCases.getPriceAtDate(productId, LocalDate.of(2024, 4, 15));
		useCases.getPriceAtDate(productId, LocalDate.of(2030, 1, 1));

		Set<String> keys = redisTemplate.keys("products::current-price::" + productId + "::*");
		assertEquals(Set.of(
				currentPriceKey(productId, 2, LocalDate.of(2024, 4, 15)),
				currentPriceKey(productId, 2, LocalDate.of(2030, 1, 1))), keys);
	}

	@Test
	void historySerializesDatesDecimalsNullsAndImmutableLists() {
		long productId = createProduct("History");
		addPrice(productId, "10.25", "2024-01-01", "2024-06-30");
		addPrice(productId, "20.50", "2024-07-01", null);

		ProductHistoryResult first = useCases.getPriceHistory(productId);
		String key = historyKey(productId, 2);
		String rawJson = redisTemplate.opsForValue().get(key);

		assertEquals(2, first.prices().size());
		assertNotNull(rawJson);
		assertTrue(rawJson.contains("initDate"));
		assertTrue(rawJson.contains("2024-01-01"));
		assertTrue(rawJson.contains("20.50"));
		assertTrue(rawJson.contains("endDate"));
		assertTrue(rawJson.contains("null"));
		assertTtl(key, 120);

		jdbcTemplate.update("DELETE FROM prices WHERE product_id = ?", productId);
		ProductHistoryResult cached = useCases.getPriceHistory(productId);
		assertEquals(first, cached);
		assertEquals(List.copyOf(cached.prices()), cached.prices());
	}

	@Test
	void successfulPriceAdditionInvalidatesOnlyTheChangedProductByVersion() {
		long firstProduct = createProductWithPrice("First", "10.00", "2024-01-01", "2024-06-30");
		long secondProduct = createProductWithPrice("Second", "30.00", "2024-01-01", "2024-06-30");

		useCases.getPriceAtDate(firstProduct, FIRST_DATE);
		useCases.getPriceHistory(firstProduct);
		useCases.getPriceAtDate(secondProduct, FIRST_DATE);
		useCases.getPriceHistory(secondProduct);

		addPrice(firstProduct, "20.00", "2024-07-01", null);

		assertEquals("2", redisTemplate.opsForValue().get(RedisProductCacheVersionStore.versionKey(firstProduct)));
		assertEquals("1", redisTemplate.opsForValue().get(RedisProductCacheVersionStore.versionKey(secondProduct)));
		assertTrue(redisTemplate.hasKey(currentPriceKey(secondProduct, 1, FIRST_DATE)));
		assertTrue(redisTemplate.hasKey(historyKey(secondProduct, 1)));

		ProductHistoryResult refreshed = useCases.getPriceHistory(firstProduct);
		assertEquals(2, refreshed.prices().size());
		assertEquals(new BigDecimal("20.00"),
				useCases.getPriceAtDate(firstProduct, LocalDate.of(2030, 1, 1)).value());
		assertTrue(redisTemplate.hasKey(historyKey(firstProduct, 2)));
		assertTrue(redisTemplate.hasKey(currentPriceKey(
				firstProduct, 2, LocalDate.of(2030, 1, 1))));
	}

	@Test
	void emptyRedisFallsBackToPostgreSQLAndRepopulatesCache() {
		long productId = createProductWithPrice("Empty Redis", "42.00", "2024-01-01", null);
		redisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});

		assertEquals(new BigDecimal("42.00"),
				useCases.getPriceAtDate(productId, LocalDate.of(2030, 1, 1)).value());
		assertTrue(redisTemplate.hasKey(currentPriceKey(productId, 0, LocalDate.of(2030, 1, 1))));
	}

	private long createProductWithPrice(
			String name,
			String value,
			String initDate,
			String endDate) {
		long productId = createProduct(name);
		addPrice(productId, value, initDate, endDate);
		return productId;
	}

	private long createProduct(String name) {
		ProductResult product = useCases.createProduct(new CreateProductCommand(name, "Cache integration test"));
		return product.id();
	}

	private void addPrice(long productId, String value, String initDate, String endDate) {
		useCases.addPrice(productId, new AddPriceCommand(
				new BigDecimal(value),
				LocalDate.parse(initDate),
				endDate == null ? null : LocalDate.parse(endDate)));
	}

	private void assertTtl(String key, long maximumSeconds) {
		Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
		assertNotNull(ttl);
		assertTrue(ttl > 0 && ttl <= maximumSeconds, () -> "Unexpected TTL for " + key + ": " + ttl);
	}

	private static String currentPriceKey(long productId, long version, LocalDate date) {
		return "products::current-price::" + productId + "::v" + version + "::" + date;
	}

	private static String historyKey(long productId, long version) {
		return "products::price-history::" + productId + "::v" + version;
	}

}

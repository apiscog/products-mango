package com.mango.products.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.products.RedisIntegrationTestBase;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisCacheApiIT extends RedisIntegrationTestBase {

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void httpContractStaysStableAndCachedDataRefreshesAfterAddingPrice() throws Exception {
		ResponseEntity<String> created = restTemplate.postForEntity(
				"/products",
				new ProductRequest("HTTP cached product", "Redis E2E"),
				String.class);
		assertEquals(HttpStatus.CREATED, created.getStatusCode());
		long productId = json(created).required("id").longValue();

		assertEquals(HttpStatus.CREATED, addPrice(
				productId, "99.99", "2024-01-01", "2024-06-30").getStatusCode());

		ResponseEntity<String> firstPrice = getPrice(productId, "2024-04-15");
		ResponseEntity<String> cachedPrice = getPrice(productId, "2024-04-15");
		assertEquals(firstPrice.getBody(), cachedPrice.getBody());
		assertEquals(new BigDecimal("99.99"), json(firstPrice).required("value").decimalValue());

		ResponseEntity<String> firstHistory = getHistory(productId);
		ResponseEntity<String> cachedHistory = getHistory(productId);
		assertEquals(firstHistory.getBody(), cachedHistory.getBody());
		assertEquals(1, json(firstHistory).required("prices").size());

		assertTrue(redisTemplate.hasKey(
				"products::current-price::" + productId + "::v1::2024-04-15"));
		assertTrue(redisTemplate.hasKey("products::price-history::" + productId + "::v1"));

		assertEquals(HttpStatus.CREATED, addPrice(
				productId, "149.99", "2024-07-01", null).getStatusCode());

		ResponseEntity<String> refreshedHistory = getHistory(productId);
		assertEquals(2, json(refreshedHistory).required("prices").size());
		ResponseEntity<String> newCurrentPrice = getPrice(productId, "2030-01-01");
		assertEquals(new BigDecimal("149.99"),
				json(newCurrentPrice).required("value").decimalValue());
		assertTrue(redisTemplate.hasKey("products::price-history::" + productId + "::v2"));
		assertTrue(redisTemplate.hasKey(
				"products::current-price::" + productId + "::v2::2030-01-01"));
	}

	private ResponseEntity<String> addPrice(
			long productId,
			String value,
			String initDate,
			String endDate) {
		return restTemplate.postForEntity(
				"/products/{id}/prices",
				new PriceRequest(new BigDecimal(value), LocalDate.parse(initDate),
						endDate == null ? null : LocalDate.parse(endDate)),
				String.class,
				productId);
	}

	private ResponseEntity<String> getPrice(long productId, String date) {
		return restTemplate.getForEntity(
				"/products/{id}/prices?date={date}", String.class, productId, date);
	}

	private ResponseEntity<String> getHistory(long productId) {
		return restTemplate.getForEntity("/products/{id}/prices", String.class, productId);
	}

	private JsonNode json(ResponseEntity<String> response) throws Exception {
		return objectMapper.readTree(response.getBody());
	}

	private record ProductRequest(String name, String description) {
	}

	private record PriceRequest(BigDecimal value, LocalDate initDate, LocalDate endDate) {
	}

}

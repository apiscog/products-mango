package com.mango.products.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.products.PostgreSQLIntegrationTestBase;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisUnavailableApiIT extends PostgreSQLIntegrationTestBase {

	private static final int UNAVAILABLE_REDIS_PORT = findUnusedPort();

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void configureUnavailableRedis(DynamicPropertyRegistry registry) {
		registry.add("spring.cache.type", () -> "redis");
		registry.add("spring.data.redis.host", () -> "localhost");
		registry.add("spring.data.redis.port", () -> UNAVAILABLE_REDIS_PORT);
		registry.add("spring.data.redis.connect-timeout", () -> "100ms");
		registry.add("spring.data.redis.timeout", () -> "200ms");
	}

	@Test
	void productReadsRemainAvailableThroughPostgreSQLWhenRedisIsDown() throws Exception {
		ResponseEntity<String> created = restTemplate.postForEntity(
				"/products", new ProductRequest("Fail open", "Redis unavailable"), String.class);
		assertEquals(HttpStatus.CREATED, created.getStatusCode());
		long productId = objectMapper.readTree(created.getBody()).required("id").longValue();

		ResponseEntity<String> priceCreated = restTemplate.postForEntity(
				"/products/{id}/prices",
				new PriceRequest(new BigDecimal("77.77"), LocalDate.of(2024, 1, 1), null),
				String.class,
				productId);
		assertEquals(HttpStatus.CREATED, priceCreated.getStatusCode());

		ResponseEntity<String> current = restTemplate.getForEntity(
				"/products/{id}/prices?date=2030-01-01", String.class, productId);
		assertEquals(HttpStatus.OK, current.getStatusCode());
		JsonNode currentBody = objectMapper.readTree(current.getBody());
		assertEquals(new BigDecimal("77.77"), currentBody.required("value").decimalValue());

		ResponseEntity<String> history = restTemplate.getForEntity(
				"/products/{id}/prices", String.class, productId);
		assertEquals(HttpStatus.OK, history.getStatusCode());
		assertEquals(1, objectMapper.readTree(history.getBody()).required("prices").size());
	}

	private static int findUnusedPort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to allocate an unused Redis test port", exception);
		}
	}

	private record ProductRequest(String name, String description) {
	}

	private record PriceRequest(BigDecimal value, LocalDate initDate, LocalDate endDate) {
	}

}

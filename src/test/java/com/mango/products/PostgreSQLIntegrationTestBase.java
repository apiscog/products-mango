package com.mango.products;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class PostgreSQLIntegrationTestBase {

	private static final PostgreSQLContainer<?> POSTGRESQL =
			new PostgreSQLContainer<>("postgres:17-alpine");

	static {
		POSTGRESQL.start();
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void configurePostgreSQL(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRESQL::getUsername);
		registry.add("spring.datasource.password", POSTGRESQL::getPassword);
		registry.add("products.security.jwt.public-key-location",
				() -> "classpath:security/test-public-key.pem");
		registry.add("products.security.jwt.issuer", () -> "products-challenge-dev");
		registry.add("products.security.jwt.audience", () -> "products-api");
	}

	@BeforeEach
	protected void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM prices");
		jdbcTemplate.update("DELETE FROM products");
	}

}

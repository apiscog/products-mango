package com.mango.products.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Date;
import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrencyMigrationIT {

	@Test
	void migratesExistingV1PriceToEurWithoutChangingDataOrTemporalGuarantee() {
		try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
			postgres.start();
			Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.target(MigrationVersion.fromVersion("1"))
					.load()
					.migrate();
			JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
					postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
			Long productId = jdbc.queryForObject(
					"INSERT INTO products(name) VALUES ('Legacy') RETURNING id", Long.class);
			Long priceId = jdbc.queryForObject("""
					INSERT INTO prices(product_id, value, init_date, end_date)
					VALUES (?, 99.99, DATE '2024-01-01', DATE '2024-06-30')
					RETURNING id
					""", Long.class, productId);

			Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration")
					.load()
					.migrate();

			var migrated = jdbc.queryForMap(
					"SELECT id, product_id, value, currency, init_date, end_date FROM prices WHERE id = ?",
					priceId);
			assertEquals(priceId, migrated.get("id"));
			assertEquals(productId, migrated.get("product_id"));
			assertEquals(0, new BigDecimal("99.99").compareTo((BigDecimal) migrated.get("value")));
			assertEquals("EUR", migrated.get("currency"));
			assertEquals(LocalDate.of(2024, 1, 1), ((Date) migrated.get("init_date")).toLocalDate());
			assertEquals(LocalDate.of(2024, 6, 30), ((Date) migrated.get("end_date")).toLocalDate());

			assertSqlState("23502", () -> jdbc.update("""
					INSERT INTO prices(product_id, value, currency, init_date, end_date)
					VALUES (?, 10.00, NULL, DATE '2024-07-01', DATE '2024-12-31')
					""", productId));
			assertSqlState("23514", () -> jdbc.update("""
					INSERT INTO prices(product_id, value, currency, init_date, end_date)
					VALUES (?, 10.00, 'CAD', DATE '2024-07-01', DATE '2024-12-31')
					""", productId));
			assertSqlState("23P01", () -> jdbc.update("""
					INSERT INTO prices(product_id, value, currency, init_date, end_date)
					VALUES (?, 110.00, 'USD', DATE '2024-03-01', DATE '2024-08-31')
					""", productId));
		}
	}

	private static void assertSqlState(String expected, Runnable statement) {
		RuntimeException exception = assertThrows(RuntimeException.class, statement::run);
		Throwable cause = exception;
		while (cause != null && !(cause instanceof SQLException)) {
			cause = cause.getCause();
		}
		assertEquals(expected, ((SQLException) cause).getSQLState());
	}
}

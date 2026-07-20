package com.mango.products.adapter.out.exchange;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.mango.products.application.exception.ExchangeRateUnavailableException;
import com.mango.products.domain.model.CurrencyCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FawazExchangeRateAdapterTest {

	private static final LocalDate DATE = LocalDate.of(2024, 4, 15);
	private HttpServer primary;
	private HttpServer fallback;

	@AfterEach
	void stopServers() {
		if (primary != null) {
			primary.stop(0);
		}
		if (fallback != null) {
			fallback.stop(0);
		}
	}

	@Test
	void primaryUsesHistoricalDateLowercaseBaseAndRequiredHeaders() throws Exception {
		AtomicReference<HttpExchange> request = new AtomicReference<>();
		primary = server(exchange -> {
			request.set(exchange);
			json(exchange, 200, validBody("1.06754321"));
		});
		fallback = server(exchange -> json(exchange, 500, "{}"));
		FawazExchangeRateAdapter adapter = adapter(Duration.ofSeconds(1), Duration.ofSeconds(1));

		var rate = adapter.getRate(CurrencyCode.EUR, CurrencyCode.USD, DATE);

		assertEquals("1.06754321", rate.rate().toPlainString());
		assertEquals(DATE, rate.date());
		assertEquals("/2024-04-15/currencies/eur.json", request.get().getRequestURI().getPath());
		assertEquals("application/json", request.get().getRequestHeaders().getFirst("Accept"));
		assertEquals("Products-API/1.0", request.get().getRequestHeaders().getFirst("User-Agent"));
	}

	@Test
	void serverErrorUsesFallbackExactlyOnce() throws Exception {
		AtomicInteger primaryCalls = new AtomicInteger();
		AtomicInteger fallbackCalls = new AtomicInteger();
		primary = server(exchange -> {
			primaryCalls.incrementAndGet();
			json(exchange, 503, "{}");
		});
		fallback = server(exchange -> {
			fallbackCalls.incrementAndGet();
			json(exchange, 200, validBody("1.08"));
		});

		assertEquals("1.08", adapter(Duration.ofSeconds(1), Duration.ofSeconds(1))
				.getRate(CurrencyCode.EUR, CurrencyCode.USD, DATE).rate().toPlainString());
		assertEquals(1, primaryCalls.get());
		assertEquals(1, fallbackCalls.get());
	}

	@Test
	void invalidJsonMissingBaseTargetAndNonPositiveRatesUseFallback() throws Exception {
		String[] invalidBodies = {
				"not-json",
				"""
				{ "date": "2024-04-15" }
				""",
				"""
				{ "date": "2024-04-15", "eur": {} }
				""",
				"""
				{ "date": "2024-04-15", "eur": { "usd": 0 } }
				""",
				"""
				{ "date": "2024-04-15", "eur": { "usd": -1 } }
				""",
				"""
				{ "date": "2024-04-14", "eur": { "usd": 1.1 } }
				"""
		};
		for (String invalidBody : invalidBodies) {
			stopServers();
			primary = server(exchange -> json(exchange, 200, invalidBody));
			fallback = server(exchange -> json(exchange, 200, validBody("1.09")));
			assertEquals("1.09", adapter(Duration.ofSeconds(1), Duration.ofSeconds(1))
					.getRate(CurrencyCode.EUR, CurrencyCode.USD, DATE).rate().toPlainString());
		}
	}

	@Test
	void timeoutUsesFallback() throws Exception {
		primary = server(exchange -> {
			try {
				Thread.sleep(300);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			json(exchange, 200, validBody("1.01"));
		});
		fallback = server(exchange -> json(exchange, 200, validBody("1.07")));

		assertEquals("1.07", adapter(Duration.ofSeconds(1), Duration.ofMillis(100))
				.getRate(CurrencyCode.EUR, CurrencyCode.USD, DATE).rate().toPlainString());
	}

	@Test
	void bothProvidersFailWithApplicationException() throws Exception {
		primary = server(exchange -> json(exchange, 500, "{}"));
		fallback = server(exchange -> json(exchange, 200, """
				{ "date": "2024-04-15", "eur": {} }
				"""));

		assertThrows(
				ExchangeRateUnavailableException.class,
				() -> adapter(Duration.ofSeconds(1), Duration.ofSeconds(1))
						.getRate(CurrencyCode.EUR, CurrencyCode.USD, DATE));
	}

	@Test
	void nonNotFoundClientErrorDoesNotCallFallback() throws Exception {
		AtomicInteger fallbackCalls = new AtomicInteger();
		primary = server(exchange -> json(exchange, 400, "{}"));
		fallback = server(exchange -> {
			fallbackCalls.incrementAndGet();
			json(exchange, 200, validBody("1.08"));
		});

		assertThrows(
				ExchangeRateUnavailableException.class,
				() -> adapter(Duration.ofSeconds(1), Duration.ofSeconds(1))
						.getRate(CurrencyCode.EUR, CurrencyCode.USD, DATE));
		assertEquals(0, fallbackCalls.get());
	}

	private FawazExchangeRateAdapter adapter(Duration connectTimeout, Duration readTimeout) {
		ExchangeRateProperties properties = new ExchangeRateProperties();
		properties.setPrimaryUrl(url(primary));
		properties.setFallbackUrl(url(fallback));
		properties.setConnectTimeout(connectTimeout);
		properties.setReadTimeout(readTimeout);
		return new FawazExchangeRateAdapter(
				new ExchangeRateHttpConfiguration().exchangeRateRestClient(properties), properties);
	}

	private static String url(HttpServer server) {
		return "http://localhost:" + server.getAddress().getPort()
				+ "/{date}/currencies/{base}.json";
	}

	private static HttpServer server(Handler handler) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> handler.handle(exchange));
		server.start();
		return server;
	}

	private static String validBody(String rate) {
		return """
				{ "date": "2024-04-15", "eur": { "usd": %s } }
				""".formatted(rate);
	}

	private static void json(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@FunctionalInterface
	private interface Handler {
		void handle(HttpExchange exchange) throws IOException;
	}
}

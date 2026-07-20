package com.mango.products.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.products.PostgreSQLIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.mango.products.application.exception.ExchangeRateUnavailableException;
import com.mango.products.application.model.ExchangeRate;
import com.mango.products.application.port.out.ExchangeRateProvider;
import com.mango.products.domain.model.CurrencyCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProductsApiIT.ExchangeTestConfiguration.class)
class ProductsApiIT extends PostgreSQLIntegrationTestBase {

    private static final Set<String> PRODUCT_FIELDS = Set.of("id", "name", "description");
    private static final Set<String> PRICE_FIELDS = Set.of("value", "currency", "initDate", "endDate");
    private static final Set<String> CURRENT_PRICE_FIELDS = Set.of("value", "currency");
    private static final Set<String> HISTORY_FIELDS = Set.of("name", "description", "prices");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StubExchangeRateProvider exchangeRateProvider;

    @BeforeEach
    void resetExchangeRateProvider() {
        exchangeRateProvider.reset();
    }

    @Test
    void createsAndPersistsProductWithExactHttpContract() throws Exception {
        ResponseEntity<String> response = post("/products", """
                {"name":"Zapatillas deportivas","description":"Modelo 2025 edición limitada"}
                """);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertJsonContentType(response);
        JsonNode body = json(response);
        assertOnlyFields(body, PRODUCT_FIELDS);
        long productId = body.required("id").longValue();
        assertTrue(productId > 0);
        assertEquals("Zapatillas deportivas", body.required("name").textValue());
        assertEquals("Modelo 2025 edición limitada", body.required("description").textValue());
        assertEquals("/products/" + productId, response.getHeaders().getLocation().toString());

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT name, description FROM products WHERE id = ?", productId);
        assertEquals("Zapatillas deportivas", stored.get("name"));
        assertEquals("Modelo 2025 edición limitada", stored.get("description"));
    }

    @Test
    void addsFiniteAndOpenPricesAndPersistsExactValues() throws Exception {
        long productId = createProduct("Product", "Description");

        ResponseEntity<String> finite = addPrice(productId, "99.99", "2024-01-01", "2024-06-30");
        ResponseEntity<String> open = addPrice(productId, "109.90", "2024-07-01", null);

        assertPriceCreated(finite, "99.99", "2024-01-01", "2024-06-30");
        assertPriceCreated(open, "109.90", "2024-07-01", null);
        assertTrue(json(open).has("endDate"));
        assertTrue(json(open).get("endDate").isNull());

        List<Map<String, Object>> stored = jdbcTemplate.queryForList("""
                SELECT value, init_date::text AS init_date, end_date::text AS end_date
                FROM prices
                WHERE product_id = ?
                ORDER BY init_date, id
                """, productId);
        assertEquals(2, stored.size());
        assertEquals(0, new BigDecimal("99.99").compareTo((BigDecimal) stored.get(0).get("value")));
        assertEquals("2024-01-01", stored.get(0).get("init_date"));
        assertEquals("2024-06-30", stored.get(0).get("end_date"));
        assertEquals(0, new BigDecimal("109.90").compareTo((BigDecimal) stored.get(1).get("value")));
        assertEquals("2024-07-01", stored.get(1).get("init_date"));
        assertEquals(null, stored.get(1).get("end_date"));
    }

    @Test
    void supportsExplicitCurrencyOriginalReadingAndHistoricalConversion() throws Exception {
        long eurProduct = createProduct("EUR product", null);
        ResponseEntity<String> defaultEur = addPrice(
                eurProduct, "99.99", "2024-01-01", "2024-06-30");
        assertEquals("EUR", json(defaultEur).required("currency").textValue());

        ResponseEntity<String> convertedResponse = get(
                "/products/" + eurProduct + "/prices?date=2024-04-15&currency=usd");
        assertEquals(HttpStatus.OK, convertedResponse.getStatusCode());
        JsonNode converted = json(convertedResponse);
        assertEquals(Set.of(
                        "value", "currency", "originalValue", "originalCurrency",
                        "exchangeRate", "exchangeRateDate"),
                fieldNames(converted));
        assertEquals(0, new BigDecimal("106.74").compareTo(converted.required("value").decimalValue()));
        assertEquals("USD", converted.required("currency").textValue());
        assertEquals("EUR", converted.required("originalCurrency").textValue());
        assertEquals("2024-04-15", converted.required("exchangeRateDate").textValue());

        long usdProduct = createProduct("USD product", null);
        ResponseEntity<String> explicitUsd = post("/products/" + usdProduct + "/prices", """
                {"value":100.00,"currency":"usd","initDate":"2024-01-01","endDate":"2024-06-30"}
                """);
        assertEquals(HttpStatus.CREATED, explicitUsd.getStatusCode());
        assertEquals("USD", json(explicitUsd).required("currency").textValue());
        assertEquals("USD", json(get(
                "/products/" + usdProduct + "/prices?date=2024-04-15"))
                .required("currency").textValue());
    }

    @Test
    void sameCurrencySkipsProviderUnsupportedCurrencyIsBadRequestAndOutageIs503() throws Exception {
        long productId = createProduct("Product", null);
        addPrice(productId, "99.99", "2024-01-01", "2024-06-30");

        ResponseEntity<String> same = get(
                "/products/" + productId + "/prices?date=2024-04-15&currency=EUR");
        assertEquals(HttpStatus.OK, same.getStatusCode());
        assertEquals(0, exchangeRateProvider.calls);
        assertEquals(0, BigDecimal.ONE.compareTo(json(same).required("exchangeRate").decimalValue()));

        ResponseEntity<String> unsupported = get(
                "/products/" + productId + "/prices?date=2024-04-15&currency=CAD");
        assertError(unsupported, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "/products/" + productId + "/prices");

        exchangeRateProvider.unavailable = true;
        ResponseEntity<String> unavailable = get(
                "/products/" + productId + "/prices?date=2024-04-15&currency=USD");
        assertError(unavailable, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "/products/" + productId + "/prices");
    }

    @Test
    void getsFinitePriceAtInclusiveBoundariesAndNotAfterEnd() throws Exception {
        long productId = createProduct("Product", null);
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "99.99", "2024-01-01", "2024-06-30").getStatusCode());

        assertCurrentPrice(productId, "2024-01-01", "99.99");
        assertCurrentPrice(productId, "2024-06-30", "99.99");

        ResponseEntity<String> afterEnd = get("/products/" + productId + "/prices?date=2024-07-01");
        assertError(afterEnd, HttpStatus.NOT_FOUND, "PRICE_NOT_FOUND",
                "/products/" + productId + "/prices");
    }

    @Test
    void getsOpenEndedPriceOnFutureDate() throws Exception {
        long productId = createProduct("Product", null);
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "45.67", "2025-01-01", null).getStatusCode());

        assertCurrentPrice(productId, "2099-12-31", "45.67");
    }

    @Test
    void returnsOrderedHistoryWithStrictNestedContract() throws Exception {
        long productId = createProduct("Product", "Description");
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "30.00", "2025-01-01", null).getStatusCode());
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "10.00", "2024-01-01", "2024-03-31").getStatusCode());
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "20.00", "2024-04-01", "2024-12-31").getStatusCode());

        ResponseEntity<String> response = get("/products/" + productId + "/prices");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertJsonContentType(response);
        JsonNode body = json(response);
        assertOnlyFields(body, HISTORY_FIELDS);
        assertEquals("Product", body.required("name").textValue());
        assertEquals("Description", body.required("description").textValue());
        JsonNode prices = body.required("prices");
        assertEquals(3, prices.size());
        for (JsonNode price : prices) {
            assertOnlyFields(price, PRICE_FIELDS);
            assertEquals("EUR", price.required("currency").textValue());
            assertFalse(price.has("id"));
            assertFalse(price.has("validity"));
        }
        assertEquals("2024-01-01", prices.get(0).required("initDate").textValue());
        assertEquals("2024-04-01", prices.get(1).required("initDate").textValue());
        assertEquals("2025-01-01", prices.get(2).required("initDate").textValue());
        assertTrue(prices.get(2).required("endDate").isNull());
    }

    @Test
    void returnsEmptyHistoryForProductWithoutPrices() throws Exception {
        long productId = createProduct("Empty history", null);

        ResponseEntity<String> response = get("/products/" + productId + "/prices");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = json(response);
        assertOnlyFields(body, HISTORY_FIELDS);
        assertTrue(body.required("description").isNull());
        assertTrue(body.required("prices").isEmpty());
    }

    @Test
    void rejectsBlankProductNameWithUniformValidationError() throws Exception {
        ResponseEntity<String> response = post("/products", "{\"name\":\"   \",\"description\":null}");

        JsonNode error = assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "/products");
        assertEquals("Request validation failed", error.required("message").textValue());
        assertFalse(error.required("violations").isEmpty());
        assertEquals("name", error.required("violations").get(0).required("field").textValue());
    }

    @ParameterizedTest(name = "rejects invalid price value {0}")
    @ValueSource(strings = {"0", "99.999"})
    void rejectsInvalidPriceValues(String value) throws Exception {
        long productId = createProduct("Product", null);

        ResponseEntity<String> response = addPrice(productId, value, "2024-01-01", "2024-06-30");

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "/products/" + productId + "/prices");
        assertEquals(0, priceCount(productId));
    }

    @Test
    void rejectsEqualPriceDatesThroughDomainValidation() throws Exception {
        long productId = createProduct("Product", null);

        ResponseEntity<String> response = addPrice(productId, "10.00", "2024-01-01", "2024-01-01");

        assertError(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "/products/" + productId + "/prices");
        assertEquals(0, priceCount(productId));
    }

    @Test
    void rejectsMalformedDateMalformedJsonAndNonPositiveId() throws Exception {
        long productId = createProduct("Product", null);

        ResponseEntity<String> malformedDate = get(
                "/products/" + productId + "/prices?date=15/04/2024");
        assertError(malformedDate, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "/products/" + productId + "/prices");

        ResponseEntity<String> malformedJson = post("/products", "{");
        assertError(malformedJson, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "/products");

        ResponseEntity<String> invalidId = get("/products/0/prices");
        assertError(invalidId, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "/products/0/prices");
    }

    @Test
    void distinguishesMissingProductsFromMissingPrice() throws Exception {
        long missingId = Long.MAX_VALUE;

        assertError(addPrice(missingId, "10.00", "2024-01-01", null),
                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "/products/" + missingId + "/prices");
        assertError(get("/products/" + missingId + "/prices?date=2024-01-01"),
                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "/products/" + missingId + "/prices");
        assertError(get("/products/" + missingId + "/prices"),
                HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "/products/" + missingId + "/prices");

        long existingId = createProduct("Product", null);
        assertError(get("/products/" + existingId + "/prices?date=2024-01-01"),
                HttpStatus.NOT_FOUND, "PRICE_NOT_FOUND", "/products/" + existingId + "/prices");
    }

    @Test
    void rejectsRealOverlapsAndKeepsOnlyValidPeriods() throws Exception {
        long productId = createProduct("Product", null);
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "10.00", "2024-01-01", "2024-06-30").getStatusCode());

        assertOverlap(productId, "11.00", "2024-06-30", "2024-12-31");
        assertOverlap(productId, "12.00", "2024-02-01", "2024-03-01");
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "13.00", "2024-07-01", "2024-12-31").getStatusCode());
        assertEquals(HttpStatus.CREATED,
                addPrice(productId, "14.00", "2025-01-01", null).getStatusCode());
        assertOverlap(productId, "15.00", "2026-01-01", "2026-12-31");

        ResponseEntity<String> history = get("/products/" + productId + "/prices");
        assertEquals(HttpStatus.OK, history.getStatusCode());
        assertEquals(3, json(history).required("prices").size());
        assertEquals(3, priceCount(productId));
    }

    @Test
    void concurrentOverlappingHttpRequestsYieldOneCreatedAndOneConflict() throws Exception {
        long productId = createProduct("Concurrent product", null);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<ResponseEntity<String>> responses = executeConcurrently(
                () -> postAfterBarrier(barrier, productId, "10.00", "2024-01-01", "2024-06-30"),
                () -> postAfterBarrier(barrier, productId, "20.00", "2024-06-30", "2024-12-31")
        );

        assertEquals(Set.of(HttpStatus.CREATED, HttpStatus.CONFLICT), responseStatuses(responses));
        ResponseEntity<String> conflict = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .findFirst().orElseThrow();
        assertEquals("PRICE_OVERLAP", json(conflict).required("code").textValue());
        assertEquals(1, priceCount(productId));
        assertEquals(1, json(get("/products/" + productId + "/prices")).required("prices").size());
    }

    @Test
    void concurrentNonOverlappingHttpRequestsBothSucceed() throws Exception {
        long productId = createProduct("Concurrent product", null);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<ResponseEntity<String>> responses = executeConcurrently(
                () -> postAfterBarrier(barrier, productId, "10.00", "2024-01-01", "2024-06-30"),
                () -> postAfterBarrier(barrier, productId, "20.00", "2024-07-01", "2024-12-31")
        );

        assertTrue(responses.stream().allMatch(response -> response.getStatusCode() == HttpStatus.CREATED));
        assertEquals(2, priceCount(productId));
        assertEquals(2, json(get("/products/" + productId + "/prices")).required("prices").size());
    }

    @Test
    void exposesMandatoryPathsInOpenApiDocument() throws Exception {
        ResponseEntity<String> response = get("/v3/api-docs");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertJsonContentType(response);
        JsonNode paths = json(response).required("paths");
        assertTrue(paths.has("/products"));
        assertTrue(paths.has("/products/{id}/prices"));
    }

    private long createProduct(String name, String description) throws Exception {
        String body = objectMapper.writeValueAsString(new ProductRequest(name, description));
        ResponseEntity<String> response = post("/products", body);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        return json(response).required("id").longValue();
    }

    private ResponseEntity<String> addPrice(
            long productId,
            String value,
            String initDate,
            String endDate
    ) throws Exception {
        String body = objectMapper.writeValueAsString(new PriceRequest(new BigDecimal(value), initDate, endDate));
        return post("/products/" + productId + "/prices", body);
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return restTemplate.getForEntity(url(path), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private static void assertJsonContentType(ResponseEntity<String> response) {
        MediaType contentType = response.getHeaders().getContentType();
        assertNotNull(contentType);
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(contentType));
    }

    private static void assertOnlyFields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private void assertPriceCreated(
            ResponseEntity<String> response,
            String value,
            String initDate,
            String endDate
    ) throws Exception {
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertJsonContentType(response);
        JsonNode body = json(response);
        assertOnlyFields(body, PRICE_FIELDS);
        assertEquals(0, new BigDecimal(value).compareTo(body.required("value").decimalValue()));
        assertEquals("EUR", body.required("currency").textValue());
        assertEquals(initDate, body.required("initDate").textValue());
        if (endDate == null) {
            assertTrue(body.required("endDate").isNull());
        }
        else {
            assertEquals(endDate, body.required("endDate").textValue());
        }
    }

    private void assertCurrentPrice(long productId, String date, String expectedValue) throws Exception {
        ResponseEntity<String> response = get("/products/" + productId + "/prices?date=" + date);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertJsonContentType(response);
        JsonNode body = json(response);
        assertOnlyFields(body, CURRENT_PRICE_FIELDS);
        assertEquals(0, new BigDecimal(expectedValue).compareTo(body.required("value").decimalValue()));
        assertEquals("EUR", body.required("currency").textValue());
    }

    private JsonNode assertError(
            ResponseEntity<String> response,
            HttpStatus status,
            String code,
            String path
    ) throws Exception {
        assertEquals(status, response.getStatusCode());
        assertJsonContentType(response);
        JsonNode error = json(response);
        assertOnlyFields(error, Set.of("timestamp", "status", "code", "message", "path", "violations"));
        assertFalse(error.required("timestamp").textValue().isBlank());
        assertEquals(status.value(), error.required("status").intValue());
        assertEquals(code, error.required("code").textValue());
        assertFalse(error.required("message").textValue().isBlank());
        assertEquals(path, error.required("path").textValue());
        assertTrue(error.required("violations").isArray());
        return error;
    }

    private void assertOverlap(
            long productId,
            String value,
            String initDate,
            String endDate
    ) throws Exception {
        ResponseEntity<String> response = addPrice(productId, value, initDate, endDate);
        assertError(response, HttpStatus.CONFLICT, "PRICE_OVERLAP", "/products/" + productId + "/prices");
    }

    private int priceCount(long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prices WHERE product_id = ?", Integer.class, productId);
    }

    private ResponseEntity<String> postAfterBarrier(
            CyclicBarrier barrier,
            long productId,
            String value,
            String initDate,
            String endDate
    ) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return addPrice(productId, value, initDate, endDate);
    }

    private List<ResponseEntity<String>> executeConcurrently(
            CheckedRequest first,
            CheckedRequest second
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> firstFuture = executor.submit(first::execute);
            Future<ResponseEntity<String>> secondFuture = executor.submit(second::execute);
            List<ResponseEntity<String>> responses = new ArrayList<>(2);
            responses.add(firstFuture.get(20, TimeUnit.SECONDS));
            responses.add(secondFuture.get(20, TimeUnit.SECONDS));
            return responses;
        }
        finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Set<HttpStatus> responseStatuses(List<ResponseEntity<String>> responses) {
        Set<HttpStatus> statuses = new HashSet<>();
        responses.forEach(response -> statuses.add((HttpStatus) response.getStatusCode()));
        return statuses;
    }

    private record ProductRequest(String name, String description) {
    }

    private record PriceRequest(BigDecimal value, String initDate, String endDate) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExchangeTestConfiguration {

        @Bean
        @Primary
        StubExchangeRateProvider stubExchangeRateProvider() {
            return new StubExchangeRateProvider();
        }
    }

    static final class StubExchangeRateProvider implements ExchangeRateProvider {
        private int calls;
        private boolean unavailable;

        @Override
        public ExchangeRate getRate(CurrencyCode source, CurrencyCode target, java.time.LocalDate date) {
            calls++;
            if (unavailable) {
                throw new ExchangeRateUnavailableException();
            }
            BigDecimal rate = source == CurrencyCode.USD
                    ? new BigDecimal("0.91234567")
                    : new BigDecimal("1.0675");
            return new ExchangeRate(source, target, rate, date);
        }

        void reset() {
            calls = 0;
            unavailable = false;
        }
    }

    @FunctionalInterface
    private interface CheckedRequest {
        ResponseEntity<String> execute() throws Exception;
    }
}

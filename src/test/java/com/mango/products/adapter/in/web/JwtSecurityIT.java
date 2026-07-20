package com.mango.products.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.products.PostgreSQLIntegrationTestBase;
import com.nimbusds.jose.JWSAlgorithm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtSecurityIT extends PostgreSQLIntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publicEndpointsDoNotRequireAuthentication() throws Exception {
        assertEquals(HttpStatus.OK, exchange(HttpMethod.GET, "/actuator/health", null, null).getStatusCode());
        ResponseEntity<String> apiDocs = exchange(HttpMethod.GET, "/v3/api-docs", null, null);
        assertEquals(HttpStatus.OK, apiDocs.getStatusCode());
        JsonNode openApi = json(apiDocs);
        assertTrue(openApi.required("components").required("securitySchemes").has("bearerAuth"));
        JsonNode createProduct = openApi.required("paths").required("/products").required("post");
        assertEquals("bearerAuth", createProduct.required("security").get(0).fieldNames().next());
        assertTrue(createProduct.required("responses").has("401"));
        assertTrue(createProduct.required("responses").has("403"));
        assertEquals(HttpStatus.OK,
                exchange(HttpMethod.GET, "/swagger-ui/index.html", null, null).getStatusCode());
    }

    @Test
    void realReaderAndWriterTokensEnforceScopesAndPreserveBusinessContract() throws Exception {
        String productJson = """
                {"name":"Secured product","description":"JWT E2E"}""";
        assertSecurityError(exchange(HttpMethod.POST, "/products", productJson, null),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertSecurityError(exchange(HttpMethod.POST, "/products", productJson, JwtTestTokens.reader()),
                HttpStatus.FORBIDDEN, "FORBIDDEN");

        ResponseEntity<String> created = exchange(
                HttpMethod.POST, "/products", productJson, JwtTestTokens.writer());
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        JsonNode product = json(created);
        assertEquals(Set.of("id", "name", "description"), fields(product));
        long productId = product.required("id").longValue();

        String priceJson = """
                {"value":99.99,"initDate":"2024-01-01","endDate":null}""";
        ResponseEntity<String> priceCreated = exchange(
                HttpMethod.POST, "/products/" + productId + "/prices", priceJson, JwtTestTokens.writer());
        assertEquals(HttpStatus.CREATED, priceCreated.getStatusCode());
        assertEquals(Set.of("value", "currency", "initDate", "endDate"), fields(json(priceCreated)));

        String currentPath = "/products/" + productId + "/prices?date=2030-01-01";
        assertSecurityError(exchange(HttpMethod.GET, currentPath, null, null),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        ResponseEntity<String> current = exchange(HttpMethod.GET, currentPath, null, JwtTestTokens.reader());
        assertEquals(HttpStatus.OK, current.getStatusCode());
        assertEquals(Set.of("value", "currency"), fields(json(current)));
        ResponseEntity<String> history = exchange(
                HttpMethod.GET, "/products/" + productId + "/prices", null, JwtTestTokens.reader());
        assertEquals(HttpStatus.OK, history.getStatusCode());
        assertEquals(Set.of("name", "description", "prices"), fields(json(history)));
    }

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidTokens")
    void rejectsCryptographicallyInvalidTokens(String description, String token) throws Exception {
        assertSecurityError(exchange(HttpMethod.GET, "/products/1/prices", null, token),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    void validTokenWithoutScopeIsForbidden() throws Exception {
        Instant now = Instant.now();
        String token = JwtTestTokens.token(
                JWSAlgorithm.RS256, JwtTestTokens.privateKey(), JwtTestTokens.ISSUER,
                JwtTestTokens.AUDIENCE, null, now.minusSeconds(5), now.minusSeconds(5), now.plusSeconds(300));
        assertSecurityError(exchange(HttpMethod.GET, "/products/1/prices", null, token),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    @Test
    void malformedBearerIsUnauthorized() throws Exception {
        assertSecurityError(exchange(HttpMethod.GET, "/products/1/prices", null, "not-a-jwt"),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    private static Stream<Arguments> invalidTokens() {
        Instant now = Instant.now();
        return Stream.of(
                Arguments.of("expired token", token(JWSAlgorithm.RS256, JwtTestTokens.ISSUER,
                        JwtTestTokens.AUDIENCE, now.minusSeconds(600), now.minusSeconds(600), now.minusSeconds(60))),
                Arguments.of("future nbf", token(JWSAlgorithm.RS256, JwtTestTokens.ISSUER,
                        JwtTestTokens.AUDIENCE, now, now.plusSeconds(300), now.plusSeconds(600))),
                Arguments.of("wrong signature", JwtTestTokens.wrongSignature()),
                Arguments.of("tampered token", JwtTestTokens.tampered()),
                Arguments.of("wrong issuer", token(JWSAlgorithm.RS256, "other-issuer",
                        JwtTestTokens.AUDIENCE, now, now.minusSeconds(5), now.plusSeconds(300))),
                Arguments.of("wrong audience", token(JWSAlgorithm.RS256, JwtTestTokens.ISSUER,
                        "other-api", now, now.minusSeconds(5), now.plusSeconds(300))),
                Arguments.of("missing audience", token(JWSAlgorithm.RS256, JwtTestTokens.ISSUER,
                        null, now, now.minusSeconds(5), now.plusSeconds(300))),
                Arguments.of("missing expiration", token(JWSAlgorithm.RS256, JwtTestTokens.ISSUER,
                        JwtTestTokens.AUDIENCE, now, now.minusSeconds(5), null)),
                Arguments.of("RS512 algorithm", token(JWSAlgorithm.RS512, JwtTestTokens.ISSUER,
                        JwtTestTokens.AUDIENCE, now, now.minusSeconds(5), now.plusSeconds(300)))
        );
    }

    private static String token(
            JWSAlgorithm algorithm, String issuer, String audience,
            Instant issuedAt, Instant notBefore, Instant expiresAt
    ) {
        return JwtTestTokens.token(algorithm, JwtTestTokens.privateKey(), issuer, audience,
                "products.read", issuedAt, notBefore, expiresAt);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                method,
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private void assertSecurityError(
            ResponseEntity<String> response, HttpStatus status, String code
    ) throws Exception {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        assertFalse(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
        JsonNode error = json(response);
        assertEquals(Set.of("timestamp", "status", "code", "message", "path", "violations"), fields(error));
        assertEquals(status.value(), error.required("status").intValue());
        assertEquals(code, error.required("code").textValue());
        assertFalse(error.required("message").textValue().isBlank());
        assertTrue(error.required("violations").isEmpty());
        assertFalse(response.getBody().contains("Bearer "));
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return objectMapper.readTree(response.getBody());
    }

    private static Set<String> fields(JsonNode node) {
        java.util.HashSet<String> fields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}

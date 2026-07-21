package com.mango.products.adapter.in.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.adapter.in.web.security.JsonAccessDeniedHandler;
import com.mango.products.adapter.in.web.security.JsonAuthenticationEntryPoint;
import com.mango.products.adapter.in.web.security.SecurityConfiguration;
import com.mango.products.adapter.in.web.security.SecurityErrorResponseWriter;
import com.mango.products.application.port.in.ProductUseCases;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.ConvertedPriceResult;
import com.mango.products.application.exception.ExchangeRateUnavailableException;
import com.mango.products.domain.model.CurrencyCode;
import com.mango.products.application.port.in.result.ProductResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ProductController.class, properties = {
        "products.security.jwt.public-key-location=classpath:security/test-public-key.pem",
        "products.security.jwt.issuer=products-challenge-dev",
        "products.security.jwt.audience=products-api"
})
@Import({
        SecurityConfiguration.class,
        SecurityErrorResponseWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class
})
class ProductSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductUseCases productUseCases;

    @Test
    void rejectsProtectedGetWithoutTokenWithJson401() throws Exception {
        mockMvc.perform(get("/products/1/prices"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp", not(empty())))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/products/1/prices"))
                .andExpect(jsonPath("$.violations").isEmpty());
        verifyNoInteractions(productUseCases);
    }

    @Test
    void rejectsProtectedPostWithoutTokenWithJson401() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProductJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(productUseCases);
    }

    @Test
    void readerScopeAllowsGet() throws Exception {
        when(productUseCases.getPriceAtDate(1L, LocalDate.of(2024, 4, 15)))
                .thenReturn(new CurrentPriceResult(new BigDecimal("99.99"), CurrencyCode.EUR));

        mockMvc.perform(get("/products/1/prices").param("date", "2024-04-15")
                        .with(jwt().jwt(token -> token.subject("reader").claim("scope", "products.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(99.99));
    }

    @Test
    void readerScopeAllowsHistoricalConversion() throws Exception {
        LocalDate date = LocalDate.of(2024, 4, 15);
        when(productUseCases.getPriceAtDate(1L, date, CurrencyCode.USD))
                .thenReturn(new ConvertedPriceResult(
                        new BigDecimal("106.74"), CurrencyCode.USD,
                        new BigDecimal("99.99"), CurrencyCode.EUR,
                        new BigDecimal("1.0675"), date));

        mockMvc.perform(get("/products/1/prices")
                        .param("date", "2024-04-15")
                        .param("currency", "USD")
                        .with(jwt().jwt(token -> token.subject("reader").claim("scope", "products.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.originalCurrency").value("EUR"));
    }

    @Test
    void convertedPriceWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/products/1/prices")
                        .param("date", "2024-04-15")
                        .param("currency", "USD"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(productUseCases);
    }

    @Test
    void unsupportedCurrencyWithReaderReturnsValidationError() throws Exception {
        mockMvc.perform(get("/products/1/prices")
                        .param("date", "2024-04-15")
                        .param("currency", "CAD")
                        .with(jwt().jwt(token -> token.subject("reader").claim("scope", "products.read"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(productUseCases);
    }

    @Test
    void unavailableExchangeProviderWithReaderReturns503() throws Exception {
        LocalDate date = LocalDate.of(2024, 4, 15);
        when(productUseCases.getPriceAtDate(1L, date, CurrencyCode.USD))
                .thenThrow(new ExchangeRateUnavailableException());

        mockMvc.perform(get("/products/1/prices")
                        .param("date", "2024-04-15")
                        .param("currency", "USD")
                        .with(jwt().jwt(token -> token.subject("reader").claim("scope", "products.read"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void readerScopeCannotPost() throws Exception {
        mockMvc.perform(post("/products")
                        .with(jwt().jwt(token -> token.subject("reader").claim("scope", "products.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProductJson()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/products"));
        verifyNoInteractions(productUseCases);
    }

    @Test
    void writerTokenWithBothScopesAllowsPost() throws Exception {
        when(productUseCases.createProduct(new CreateProductCommand("Product", "Description")))
                .thenReturn(new ProductResult(1L, "Product", "Description"));

        mockMvc.perform(post("/products")
                        .with(jwt().jwt(token -> token.subject("writer")
                                .claim("scope", "products.read products.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProductJson()))
                .andExpect(status().isCreated());
    }

    @Test
    void writeScopeDoesNotImplyReadScope() throws Exception {
        mockMvc.perform(get("/products/1/prices")
                        .with(jwt().jwt(token -> token.claim("scope", "products.write"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(productUseCases);
    }

    @Test
    void unknownOrMissingScopeDoesNotGrantAccess() throws Exception {
        mockMvc.perform(get("/products/1/prices")
                        .with(jwt().jwt(token -> token.claim("scope", "products.unknown"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/products/1/prices").with(jwt()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(productUseCases);
    }

    private static String validProductJson() {
        return """
                {"name":"Product","description":"Description"}""";
    }
}

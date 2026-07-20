package com.mango.products.adapter.in.web;

import com.mango.products.adapter.in.web.error.GlobalExceptionHandler;
import com.mango.products.adapter.in.web.security.JsonAccessDeniedHandler;
import com.mango.products.adapter.in.web.security.JsonAuthenticationEntryPoint;
import com.mango.products.adapter.in.web.security.SecurityConfiguration;
import com.mango.products.adapter.in.web.security.SecurityErrorResponseWriter;
import com.mango.products.application.exception.PriceNotFoundException;
import com.mango.products.application.exception.PriceOverlapException;
import com.mango.products.application.exception.ProductNotFoundException;
import com.mango.products.application.port.in.ProductUseCases;
import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;
import com.mango.products.domain.exception.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ProductController.class, properties = {
        "products.security.jwt.public-key-location=classpath:security/test-public-key.pem",
        "products.security.jwt.issuer=products-challenge-dev",
        "products.security.jwt.audience=products-api"
})
@Import({
        GlobalExceptionHandler.class,
        SecurityConfiguration.class,
        SecurityErrorResponseWriter.class,
        JsonAuthenticationEntryPoint.class,
        JsonAccessDeniedHandler.class
})
@WithMockUser(authorities = {"SCOPE_products.read", "SCOPE_products.write"})
class ProductControllerTest {

    private static final LocalDate INIT_DATE = LocalDate.of(2024, 1, 1);
    private static final LocalDate END_DATE = LocalDate.of(2024, 6, 30);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductUseCases productUseCases;

    @Test
    void createsProductAndReturnsExactContract() throws Exception {
        when(productUseCases.createProduct(new CreateProductCommand("Zapatillas deportivas", "Modelo 2025 edición limitada")))
                .thenReturn(new ProductResult(1L, "Zapatillas deportivas", "Modelo 2025 edición limitada"));

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Zapatillas deportivas",
                                  "description": "Modelo 2025 edición limitada"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/products/1"))
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Zapatillas deportivas"))
                .andExpect(jsonPath("$.description").value("Modelo 2025 edición limitada"));

        verify(productUseCases).createProduct(
                new CreateProductCommand("Zapatillas deportivas", "Modelo 2025 edición limitada")
        );
    }

    @Test
    void rejectsMissingProductNameWithUniformError() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"description\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.timestamp", not(empty())))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.path").value("/products"))
                .andExpect(jsonPath("$.violations[0].field").value("name"))
                .andExpect(jsonPath("$.violations[0].message").value("must not be blank"));
        verifyNoInteractions(productUseCases);
    }

    @Test
    void rejectsBlankProductName() throws Exception {
        performCreateProduct("   ", null).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsProductNameLongerThan120Characters() throws Exception {
        performCreateProduct("a".repeat(121), null).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsProductDescriptionLongerThan1000Characters() throws Exception {
        performCreateProduct("name", "a".repeat(1001)).andExpect(status().isBadRequest());
    }

    @Test
    void addsPriceAndReturnsExactContract() throws Exception {
        AddPriceCommand command = new AddPriceCommand(new BigDecimal("99.99"), INIT_DATE, END_DATE);
        when(productUseCases.addPrice(1L, command))
                .thenReturn(new PriceResult(new BigDecimal("99.99"), INIT_DATE, END_DATE));

        mockMvc.perform(post("/products/1/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPriceJson("99.99", "\"2024-06-30\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.value").value(99.99))
                .andExpect(jsonPath("$.initDate").value("2024-01-01"))
                .andExpect(jsonPath("$.endDate").value("2024-06-30"));

        verify(productUseCases).addPrice(1L, command);
    }

    @Test
    void keepsNullEndDateInAddPriceResponse() throws Exception {
        AddPriceCommand command = new AddPriceCommand(new BigDecimal("99.99"), INIT_DATE, null);
        when(productUseCases.addPrice(1L, command))
                .thenReturn(new PriceResult(new BigDecimal("99.99"), INIT_DATE, null));

        mockMvc.perform(post("/products/1/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPriceJson("99.99", "null")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endDate").value(nullValue()));
    }

    @Test
    void rejectsNullPriceValue() throws Exception {
        performAddPrice(validPriceJson("null", "null")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsZeroPriceValue() throws Exception {
        performAddPrice(validPriceJson("0", "null")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativePriceValue() throws Exception {
        performAddPrice(validPriceJson("-1", "null")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPriceWithMoreThanTwoDecimals() throws Exception {
        performAddPrice(validPriceJson("99.999", "null")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNullInitDate() throws Exception {
        mockMvc.perform(post("/products/1/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":99.99,\"initDate\":null,\"endDate\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsDomainDateValidationToBadRequest() throws Exception {
        AddPriceCommand command = new AddPriceCommand(new BigDecimal("99.99"), INIT_DATE, INIT_DATE);
        when(productUseCases.addPrice(1L, command))
                .thenThrow(new DomainValidationException("initDate must be before endDate"));

        mockMvc.perform(post("/products/1/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPriceJson("99.99", "\"2024-01-01\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("initDate must be before endDate"));
    }

    @Test
    void mapsMissingProductWhenAddingPriceToNotFound() throws Exception {
        when(productUseCases.addPrice(99L, new AddPriceCommand(new BigDecimal("99.99"), INIT_DATE, null)))
                .thenThrow(new ProductNotFoundException(99L));

        performAddPrice(99L, validPriceJson("99.99", "null"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Product was not found"))
                .andExpect(jsonPath("$.path").value("/products/99/prices"));
    }

    @Test
    void mapsOverlappingPriceToConflictWithoutInternalDetails() throws Exception {
        when(productUseCases.addPrice(1L, new AddPriceCommand(new BigDecimal("99.99"), INIT_DATE, END_DATE)))
                .thenThrow(new PriceOverlapException(1L, INIT_DATE, END_DATE));

        mockMvc.perform(post("/products/1/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPriceJson("99.99", "\"2024-06-30\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("PRICE_OVERLAP"))
                .andExpect(jsonPath("$.message").value("The price period overlaps an existing price"))
                .andExpect(jsonPath("$.violations", hasSize(0)));
    }

    @Test
    void rejectsNonPositiveProductIdWhenAddingPrice() throws Exception {
        performAddPrice(0L, validPriceJson("99.99", "null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(productUseCases);
    }

    @Test
    void returnsOnlyCurrentPriceValueForIsoDate() throws Exception {
        LocalDate date = LocalDate.of(2024, 4, 15);
        when(productUseCases.getPriceAtDate(1L, date))
                .thenReturn(new CurrentPriceResult(new BigDecimal("99.99")));

        mockMvc.perform(get("/products/1/prices").param("date", "2024-04-15"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(jsonPath("$.value").value(99.99));

        verify(productUseCases).getPriceAtDate(1L, date);
        verify(productUseCases, never()).getPriceHistory(any(Long.class));
    }

    @Test
    void rejectsNonIsoDate() throws Exception {
        mockMvc.perform(get("/products/1/prices").param("date", "15/04/2024"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("date"));
        verifyNoInteractions(productUseCases);
    }

    @Test
    void rejectsImpossibleDate() throws Exception {
        mockMvc.perform(get("/products/1/prices").param("date", "2024-02-30"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsEmptyDateInsteadOfSelectingHistory() throws Exception {
        mockMvc.perform(get("/products/1/prices").param("date", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(productUseCases, never()).getPriceHistory(1L);
    }

    @Test
    void mapsMissingProductForCurrentPriceToNotFound() throws Exception {
        LocalDate date = LocalDate.of(2024, 4, 15);
        when(productUseCases.getPriceAtDate(99L, date)).thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(get("/products/99/prices").param("date", "2024-04-15"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void mapsMissingCurrentPriceToNotFound() throws Exception {
        LocalDate date = LocalDate.of(2024, 4, 15);
        when(productUseCases.getPriceAtDate(1L, date)).thenThrow(new PriceNotFoundException(1L, date));

        mockMvc.perform(get("/products/1/prices").param("date", "2024-04-15"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRICE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Price was not found"));
    }

    @Test
    void returnsOrderedHistoryWithExactJsonNamesAndVisibleNullEndDate() throws Exception {
        when(productUseCases.getPriceHistory(1L)).thenReturn(new ProductHistoryResult(
                "Zapatillas deportivas",
                "Modelo 2025 edición limitada",
                List.of(
                        new PriceResult(new BigDecimal("99.99"), INIT_DATE, END_DATE),
                        new PriceResult(new BigDecimal("109.99"), LocalDate.of(2024, 7, 1), null)
                )
        ));

        mockMvc.perform(get("/products/1/prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.name").value("Zapatillas deportivas"))
                .andExpect(jsonPath("$.description").value("Modelo 2025 edición limitada"))
                .andExpect(jsonPath("$.prices", hasSize(2)))
                .andExpect(jsonPath("$.prices[0].*", hasSize(3)))
                .andExpect(jsonPath("$.prices[0].value").value(99.99))
                .andExpect(jsonPath("$.prices[0].initDate").value("2024-01-01"))
                .andExpect(jsonPath("$.prices[0].endDate").value("2024-06-30"))
                .andExpect(jsonPath("$.prices[1].value").value(109.99))
                .andExpect(jsonPath("$.prices[1].initDate").value("2024-07-01"))
                .andExpect(jsonPath("$.prices[1].endDate").value(nullValue()));

        verify(productUseCases).getPriceHistory(1L);
        verify(productUseCases, never()).getPriceAtDate(any(Long.class), any(LocalDate.class));
    }

    @Test
    void returnsEmptyPriceListForProductWithoutHistory() throws Exception {
        when(productUseCases.getPriceHistory(1L))
                .thenReturn(new ProductHistoryResult("Product", null, List.of()));

        mockMvc.perform(get("/products/1/prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(nullValue()))
                .andExpect(jsonPath("$.prices", hasSize(0)));
    }

    @Test
    void mapsMissingProductHistoryToNotFound() throws Exception {
        when(productUseCases.getPriceHistory(99L)).thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(get("/products/99/prices"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Malformed request body"))
                .andExpect(jsonPath("$.violations", hasSize(0)));
    }

    @Test
    void rejectsInvalidTextPriceValue() throws Exception {
        mockMvc.perform(post("/products/1/prices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPriceJson("\"invalid\"", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    private org.springframework.test.web.servlet.ResultActions performCreateProduct(String name, String description)
            throws Exception {
        String descriptionJson = description == null ? "null" : "\"" + description + "\"";
        return mockMvc.perform(post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":" + descriptionJson + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions performAddPrice(String json) throws Exception {
        return performAddPrice(1L, json);
    }

    private org.springframework.test.web.servlet.ResultActions performAddPrice(long productId, String json)
            throws Exception {
        return mockMvc.perform(post("/products/{id}/prices", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static String validPriceJson(String value, String endDate) {
        return "{\"value\":" + value + ",\"initDate\":\"2024-01-01\",\"endDate\":" + endDate + "}";
    }
}

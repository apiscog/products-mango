package com.mango.products.adapter.in.web.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

public record AddPriceRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal value,
        @Schema(description = "ISO 4217 currency code; defaults to EUR", defaultValue = "EUR",
                allowableValues = {"EUR", "USD", "GBP", "JPY", "CHF"})
        String currency,
        @NotNull LocalDate initDate,
        LocalDate endDate
) {
}

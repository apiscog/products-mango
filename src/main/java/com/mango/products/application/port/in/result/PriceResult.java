package com.mango.products.application.port.in.result;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.domain.model.CurrencyCode;

public record PriceResult(BigDecimal value, CurrencyCode currency, LocalDate initDate, LocalDate endDate) {
}

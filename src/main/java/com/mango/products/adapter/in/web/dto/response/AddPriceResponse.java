package com.mango.products.adapter.in.web.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.domain.model.CurrencyCode;

public record AddPriceResponse(BigDecimal value, CurrencyCode currency, LocalDate initDate, LocalDate endDate) {
}

package com.mango.products.adapter.in.web.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.domain.model.CurrencyCode;

public record ConvertedPriceResponse(
		BigDecimal value,
		CurrencyCode currency,
		BigDecimal originalValue,
		CurrencyCode originalCurrency,
		BigDecimal exchangeRate,
		LocalDate exchangeRateDate) {
}

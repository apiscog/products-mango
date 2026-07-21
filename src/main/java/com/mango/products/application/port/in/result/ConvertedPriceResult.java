package com.mango.products.application.port.in.result;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.domain.model.CurrencyCode;

public record ConvertedPriceResult(
		BigDecimal value,
		CurrencyCode currency,
		BigDecimal originalValue,
		CurrencyCode originalCurrency,
		BigDecimal exchangeRate,
		LocalDate exchangeRateDate) {
}

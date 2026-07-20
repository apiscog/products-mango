package com.mango.products.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.domain.exception.DomainValidationException;
import com.mango.products.domain.model.CurrencyCode;

public record ExchangeRate(
		CurrencyCode sourceCurrency,
		CurrencyCode targetCurrency,
		BigDecimal rate,
		LocalDate date) {

	public ExchangeRate {
		if (sourceCurrency == null || targetCurrency == null) {
			throw new DomainValidationException("Exchange rate currencies are required");
		}
		if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
			throw new DomainValidationException("Exchange rate must be greater than zero");
		}
		if (date == null) {
			throw new DomainValidationException("Exchange rate date is required");
		}
	}
}

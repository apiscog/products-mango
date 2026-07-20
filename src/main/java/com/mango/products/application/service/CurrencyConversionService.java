package com.mango.products.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.mango.products.application.model.ExchangeRate;
import com.mango.products.application.port.in.result.ConvertedPriceResult;
import com.mango.products.application.port.out.ExchangeRateProvider;
import com.mango.products.domain.exception.DomainValidationException;
import com.mango.products.domain.model.CurrencyCode;
import com.mango.products.domain.model.Price;

@Service
public class CurrencyConversionService {

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal IDENTITY_RATE = BigDecimal.ONE;

	private final ExchangeRateProvider exchangeRateProvider;

	public CurrencyConversionService(ExchangeRateProvider exchangeRateProvider) {
		this.exchangeRateProvider = exchangeRateProvider;
	}

	public ConvertedPriceResult convert(Price price, CurrencyCode targetCurrency, LocalDate date) {
		if (price == null) {
			throw new DomainValidationException("Price to convert is required");
		}
		if (targetCurrency == null) {
			throw new DomainValidationException("Target currency is required");
		}
		if (date == null) {
			throw new DomainValidationException("Exchange rate date is required");
		}

		ExchangeRate exchangeRate = price.getCurrency() == targetCurrency
				? new ExchangeRate(price.getCurrency(), targetCurrency, IDENTITY_RATE, date)
				: exchangeRateProvider.getRate(price.getCurrency(), targetCurrency, date);
		BigDecimal converted = price.getValue()
				.multiply(exchangeRate.rate())
				.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		return new ConvertedPriceResult(
				converted,
				targetCurrency,
				price.getValue(),
				price.getCurrency(),
				exchangeRate.rate(),
				exchangeRate.date());
	}
}

package com.mango.products.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.mango.products.application.model.ExchangeRate;
import com.mango.products.application.port.in.result.CurrentPriceResult;
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
		return convert(
				new CurrentPriceResult(price.getValue(), price.getCurrency()),
				targetCurrency,
				date);
	}

	public ConvertedPriceResult convert(
			CurrentPriceResult original,
			CurrencyCode targetCurrency,
			LocalDate date) {
		if (original == null) {
			throw new DomainValidationException("Original price is required");
		}
		if (targetCurrency == null) {
			throw new DomainValidationException("Target currency is required");
		}
		if (date == null) {
			throw new DomainValidationException("Exchange rate date is required");
		}

		ExchangeRate exchangeRate = original.currency() == targetCurrency
				? new ExchangeRate(original.currency(), targetCurrency, IDENTITY_RATE, date)
				: exchangeRateProvider.getRate(original.currency(), targetCurrency, date);
		BigDecimal converted = original.value()
				.multiply(exchangeRate.rate())
				.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		return new ConvertedPriceResult(
				converted,
				targetCurrency,
				original.value(),
				original.currency(),
				exchangeRate.rate(),
				exchangeRate.date());
	}
}

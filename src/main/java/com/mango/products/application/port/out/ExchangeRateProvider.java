package com.mango.products.application.port.out;

import java.time.LocalDate;

import com.mango.products.application.model.ExchangeRate;
import com.mango.products.domain.model.CurrencyCode;

public interface ExchangeRateProvider {

	ExchangeRate getRate(CurrencyCode source, CurrencyCode target, LocalDate date);
}

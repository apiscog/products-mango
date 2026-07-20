package com.mango.products.application.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mango.products.application.exception.ExchangeRateUnavailableException;
import com.mango.products.application.model.ExchangeRate;
import com.mango.products.application.port.out.ExchangeRateProvider;
import com.mango.products.domain.model.CurrencyCode;
import com.mango.products.domain.model.Price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrencyConversionServiceTest {

	private static final LocalDate DATE = LocalDate.of(2024, 4, 15);

	@Mock
	private ExchangeRateProvider provider;

	private CurrencyConversionService service;

	@BeforeEach
	void setUp() {
		service = new CurrencyConversionService(provider);
	}

	@Test
	void convertsWithFullBigDecimalPrecisionAndRoundsOnlyFinalAmount() {
		Price price = price(new BigDecimal("99.99"), CurrencyCode.EUR);
		BigDecimal rate = new BigDecimal("1.06754321");
		when(provider.getRate(CurrencyCode.EUR, CurrencyCode.USD, DATE))
				.thenReturn(new ExchangeRate(CurrencyCode.EUR, CurrencyCode.USD, rate, DATE));

		var result = service.convert(price, CurrencyCode.USD, DATE);

		assertEquals(new BigDecimal("106.74"), result.value());
		assertEquals(rate, result.exchangeRate());
		assertEquals(new BigDecimal("99.99"), result.originalValue());
		assertEquals(CurrencyCode.EUR, result.originalCurrency());
		assertEquals(CurrencyCode.USD, result.currency());
		assertEquals(DATE, result.exchangeRateDate());
	}

	@Test
	void supportsReverseConversion() {
		Price price = price(new BigDecimal("100.00"), CurrencyCode.USD);
		when(provider.getRate(CurrencyCode.USD, CurrencyCode.EUR, DATE))
				.thenReturn(new ExchangeRate(
						CurrencyCode.USD, CurrencyCode.EUR, new BigDecimal("0.91234567"), DATE));

		assertEquals(new BigDecimal("91.23"), service.convert(price, CurrencyCode.EUR, DATE).value());
	}

	@Test
	void sameCurrencyUsesIdentityRateWithoutProvider() {
		var result = service.convert(price(new BigDecimal("10.00"), CurrencyCode.GBP), CurrencyCode.GBP, DATE);

		assertEquals(new BigDecimal("10.00"), result.value());
		assertEquals(BigDecimal.ONE, result.exchangeRate());
		verifyNoInteractions(provider);
	}

	@Test
	void propagatesProviderUnavailability() {
		Price price = price(new BigDecimal("10.00"), CurrencyCode.EUR);
		when(provider.getRate(CurrencyCode.EUR, CurrencyCode.CHF, DATE))
				.thenThrow(new ExchangeRateUnavailableException());

		assertThrows(
				ExchangeRateUnavailableException.class,
				() -> service.convert(price, CurrencyCode.CHF, DATE));
		verify(provider).getRate(CurrencyCode.EUR, CurrencyCode.CHF, DATE);
	}

	private static Price price(BigDecimal value, CurrencyCode currency) {
		return Price.reconstitute(
				1L, 1L, value, currency, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30));
	}
}

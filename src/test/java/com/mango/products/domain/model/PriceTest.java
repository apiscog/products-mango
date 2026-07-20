package com.mango.products.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.mango.products.domain.exception.DomainValidationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriceTest {

	private static final LocalDate INIT_DATE = LocalDate.of(2024, 1, 1);
	private static final LocalDate END_DATE = LocalDate.of(2024, 6, 30);

	@Test
	void createsValidNewPrice() {
		Price price = Price.create(10L, new BigDecimal("99.99"), CurrencyCode.EUR, INIT_DATE, END_DATE);

		assertNull(price.getId());
		assertEquals(10L, price.getProductId());
		assertEquals(new BigDecimal("99.99"), price.getValue());
		assertEquals(CurrencyCode.EUR, price.getCurrency());
		assertEquals(INIT_DATE, price.getInitDate());
		assertEquals(END_DATE, price.getEndDate());
	}

	@Test
	void rejectsNullProductId() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Price.create(null, new BigDecimal("10.00"), CurrencyCode.EUR, INIT_DATE, END_DATE));

		assertEquals("Price product id is required", exception.getMessage());
	}

	@Test
	void rejectsNullValue() {
		assertThrows(
				DomainValidationException.class,
				() -> Price.create(1L, null, CurrencyCode.EUR, INIT_DATE, END_DATE));
	}

	@Test
	void rejectsZeroValue() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Price.create(1L, BigDecimal.ZERO, CurrencyCode.EUR, INIT_DATE, END_DATE));

		assertEquals("Price value must be greater than zero", exception.getMessage());
	}

	@Test
	void rejectsNegativeValue() {
		assertThrows(
				DomainValidationException.class,
				() -> Price.create(1L, new BigDecimal("-0.01"), CurrencyCode.EUR, INIT_DATE, END_DATE));
	}

	@Test
	void acceptsValuesWithOneOrTwoDecimalPlaces() {
		Price oneDecimal = Price.create(1L, new BigDecimal("10.0"), CurrencyCode.EUR, INIT_DATE, END_DATE);
		Price twoDecimals = Price.create(1L, new BigDecimal("10.25"), CurrencyCode.USD, INIT_DATE, END_DATE);

		assertEquals(new BigDecimal("10.0"), oneDecimal.getValue());
		assertEquals(new BigDecimal("10.25"), twoDecimals.getValue());
	}

	@Test
	void rejectsValueWithMoreThanTwoDecimalPlacesWithoutRounding() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Price.create(1L, new BigDecimal("10.001"), CurrencyCode.EUR, INIT_DATE, END_DATE));

		assertEquals("Price value must not have more than 2 decimal places", exception.getMessage());
	}

	@Test
	void rejectsNullInitDate() {
		assertThrows(
				DomainValidationException.class,
				() -> Price.create(1L, new BigDecimal("10.00"), CurrencyCode.EUR, null, END_DATE));
	}

	@Test
	void acceptsNullEndDateAsOpenInterval() {
		Price price = Price.create(1L, new BigDecimal("10.00"), CurrencyCode.EUR, INIT_DATE, null);

		assertNull(price.getEndDate());
	}

	@Test
	void acceptsInitDateBeforeEndDate() {
		Price price = Price.create(1L, new BigDecimal("10.00"), CurrencyCode.EUR, INIT_DATE, END_DATE);

		assertEquals(INIT_DATE, price.getInitDate());
		assertEquals(END_DATE, price.getEndDate());
	}

	@Test
	void rejectsEqualInitAndEndDates() {
		DomainValidationException exception = assertThrows(
				DomainValidationException.class,
				() -> Price.create(1L, new BigDecimal("10.00"), CurrencyCode.EUR, INIT_DATE, INIT_DATE));

		assertEquals("Price init date must be before end date", exception.getMessage());
	}

	@Test
	void rejectsInitDateAfterEndDate() {
		assertThrows(
				DomainValidationException.class,
				() -> Price.create(1L, new BigDecimal("10.00"), CurrencyCode.EUR, END_DATE, INIT_DATE));
	}

	@Test
	void reconstitutesPersistedPriceWithId() {
		Price price = Price.reconstitute(
				99L, 10L, new BigDecimal("99.99"), CurrencyCode.CHF, INIT_DATE, END_DATE);

		assertEquals(99L, price.getId());
		assertEquals(10L, price.getProductId());
		assertEquals(new BigDecimal("99.99"), price.getValue());
		assertEquals(CurrencyCode.CHF, price.getCurrency());
		assertEquals(INIT_DATE, price.getInitDate());
		assertEquals(END_DATE, price.getEndDate());
	}

	@Test
	void rejectsReconstitutionWithoutId() {
		assertThrows(
				DomainValidationException.class,
				() -> Price.reconstitute(
						null, 10L, new BigDecimal("99.99"), CurrencyCode.EUR, INIT_DATE, END_DATE));
	}

	@Test
	void acceptsEverySupportedCurrencyAndRejectsNull() {
		for (CurrencyCode currency : CurrencyCode.values()) {
			assertEquals(currency,
					Price.create(1L, BigDecimal.ONE, currency, INIT_DATE, END_DATE).getCurrency());
		}
		assertThrows(DomainValidationException.class,
				() -> Price.create(1L, BigDecimal.ONE, null, INIT_DATE, END_DATE));
	}

	@Test
	void parsesSupportedCurrenciesCaseInsensitivelyAndRejectsUnsupportedOnes() {
		assertEquals(CurrencyCode.EUR, CurrencyCode.from("eur"));
		assertEquals(CurrencyCode.JPY, CurrencyCode.from(" JpY "));
		DomainValidationException exception = assertThrows(
				DomainValidationException.class, () -> CurrencyCode.from("CAD"));
		assertEquals(
				"Unsupported currency 'CAD'. Supported currencies: EUR, USD, GBP, JPY, CHF",
				exception.getMessage());
	}

}

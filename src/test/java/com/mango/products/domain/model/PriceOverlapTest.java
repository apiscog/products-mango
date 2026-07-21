package com.mango.products.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceOverlapTest {

	@Test
	void separatedIntervalsDoNotOverlap() {
		Price first = price(1L, date(2024, 1, 1), date(2024, 6, 30));
		Price second = price(1L, date(2024, 7, 1), date(2024, 12, 31));

		assertFalse(first.overlaps(second));
	}

	@Test
	void intervalsSharingAnInclusiveBoundaryOverlap() {
		Price first = price(1L, date(2024, 1, 1), date(2024, 6, 30));
		Price second = price(1L, date(2024, 6, 30), date(2024, 12, 31));

		assertTrue(first.overlaps(second));
	}

	@Test
	void intervalContainedInsideAnotherOverlaps() {
		Price outer = price(1L, date(2024, 1, 1), date(2024, 12, 31));
		Price inner = price(1L, date(2024, 3, 1), date(2024, 4, 1));

		assertTrue(outer.overlaps(inner));
	}

	@Test
	void identicalIntervalsOverlap() {
		Price first = price(1L, date(2024, 1, 1), date(2024, 12, 31));
		Price second = price(1L, date(2024, 1, 1), date(2024, 12, 31));

		assertTrue(first.overlaps(second));
	}

	@Test
	void firstOpenIntervalOverlapsLaterFiniteInterval() {
		Price open = price(1L, date(2024, 1, 1), null);
		Price finite = price(1L, date(2025, 1, 1), date(2025, 6, 30));

		assertTrue(open.overlaps(finite));
	}

	@Test
	void secondOpenIntervalOverlapsLaterFiniteInterval() {
		Price finite = price(1L, date(2025, 1, 1), date(2025, 6, 30));
		Price open = price(1L, date(2024, 1, 1), null);

		assertTrue(finite.overlaps(open));
	}

	@Test
	void twoOpenIntervalsOverlap() {
		Price first = price(1L, date(2024, 1, 1), null);
		Price second = price(1L, date(2025, 1, 1), null);

		assertTrue(first.overlaps(second));
	}

	@Test
	void finiteIntervalEndingBeforeOpenIntervalStartsDoesNotOverlap() {
		Price open = price(1L, date(2024, 7, 1), null);
		Price finite = price(1L, date(2024, 1, 1), date(2024, 6, 30));

		assertFalse(open.overlaps(finite));
	}

	@Test
	void overlapComparisonIsSymmetric() {
		Price first = price(1L, date(2024, 1, 1), date(2024, 6, 30));
		Price second = price(1L, date(2024, 6, 30), null);

		assertEquals(first.overlaps(second), second.overlaps(first));
	}

	@Test
	void overlapComparisonOnlyUsesTemporalSemantics() {
		Price firstProduct = price(1L, CurrencyCode.EUR, date(2024, 1, 1), date(2024, 6, 30));
		Price secondProduct = price(2L, CurrencyCode.USD, date(2024, 6, 30), date(2024, 12, 31));

		assertTrue(firstProduct.overlaps(secondProduct));
	}

	private static Price price(Long productId, LocalDate initDate, LocalDate endDate) {
		return price(productId, CurrencyCode.EUR, initDate, endDate);
	}

	private static Price price(
			Long productId, CurrencyCode currency, LocalDate initDate, LocalDate endDate) {
		return Price.create(productId, new BigDecimal("10.00"), currency, initDate, endDate);
	}

	private static LocalDate date(int year, int month, int day) {
		return LocalDate.of(year, month, day);
	}

}

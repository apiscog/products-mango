package com.mango.products.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mango.products.PostgreSQLIntegrationTestBase;
import com.mango.products.adapter.out.persistence.adapter.PricePersistenceAdapter;
import com.mango.products.adapter.out.persistence.adapter.ProductPersistenceAdapter;
import com.mango.products.application.exception.PriceOverlapException;
import com.mango.products.domain.model.Price;
import com.mango.products.domain.model.CurrencyCode;
import com.mango.products.domain.model.Product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricePersistenceAdapterIT extends PostgreSQLIntegrationTestBase {

	private static final LocalDate JANUARY_FIRST = LocalDate.of(2024, 1, 1);
	private static final LocalDate JUNE_THIRTIETH = LocalDate.of(2024, 6, 30);
	private static final LocalDate JULY_FIRST = LocalDate.of(2024, 7, 1);
	private static final LocalDate DECEMBER_THIRTY_FIRST = LocalDate.of(2024, 12, 31);

	@Autowired
	private ProductPersistenceAdapter productAdapter;

	@Autowired
	private PricePersistenceAdapter priceAdapter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void savesAndRetrievesPriceWithExactDecimalPrecision() {
		long productId = createProduct("Product").getId();
		BigDecimal exactValue = new BigDecimal("12345678901234567.89");

		Price saved = priceAdapter.save(Price.create(productId, exactValue, CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH));
		Price found = priceAdapter.findHistoryByProductId(productId).getFirst();

		assertEquals(saved.getId(), found.getId());
		assertEquals(0, exactValue.compareTo(found.getValue()));
		assertEquals(CurrencyCode.EUR, found.getCurrency());
		assertEquals(JANUARY_FIRST, found.getInitDate());
		assertEquals(JUNE_THIRTIETH, found.getEndDate());
	}

	@Test
	void savesOpenEndedPrice() {
		long productId = createProduct("Product").getId();

		priceAdapter.save(Price.create(productId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, null));

		assertNull(priceAdapter.findHistoryByProductId(productId).getFirst().getEndDate());
	}

	@Test
	void findsValueAtInclusiveIntervalStart() {
		long productId = createProductWithFinitePrice();

		assertEquals(0, money("10.00").compareTo(
				priceAdapter.findAtDate(productId, JANUARY_FIRST).orElseThrow().getValue()));
	}

	@Test
	void findsValueAtInclusiveIntervalEnd() {
		long productId = createProductWithFinitePrice();

		assertEquals(0, money("10.00").compareTo(
				priceAdapter.findAtDate(productId, JUNE_THIRTIETH).orElseThrow().getValue()));
	}

	@Test
	void doesNotFindValueAfterIntervalEnd() {
		long productId = createProductWithFinitePrice();

		assertTrue(priceAdapter.findAtDate(productId, JULY_FIRST).isEmpty());
	}

	@Test
	void openEndedPriceRemainsValidInFuture() {
		long productId = createProduct("Product").getId();
		priceAdapter.save(Price.create(productId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, null));

		assertTrue(priceAdapter.findAtDate(productId, LocalDate.of(2100, 1, 1)).isPresent());
	}

	@Test
	void persistsAndReconstructsSupportedCurrencies() {
		long productId = createProduct("Currencies").getId();
		Price usd = priceAdapter.save(Price.create(
				productId, money("10.00"), CurrencyCode.USD, JANUARY_FIRST, JUNE_THIRTIETH));
		Price gbp = priceAdapter.save(Price.create(
				productId, money("20.00"), CurrencyCode.GBP, JULY_FIRST, DECEMBER_THIRTY_FIRST));

		assertEquals(CurrencyCode.USD, priceAdapter.findAtDate(productId, JANUARY_FIRST)
				.orElseThrow().getCurrency());
		assertEquals(List.of(CurrencyCode.USD, CurrencyCode.GBP),
				priceAdapter.findHistoryByProductId(productId).stream()
						.map(Price::getCurrency).toList());
		assertTrue(usd.getId() > 0);
		assertTrue(gbp.getId() > 0);
	}

	@Test
	void databaseRejectsNullAndUnsupportedCurrency() {
		long productId = createProduct("Constraints").getId();
		String insert = """
				INSERT INTO prices(product_id, value, currency, init_date, end_date)
				VALUES (?, 10.00, ?, DATE '2024-01-01', DATE '2024-06-30')
				""";

		assertThrows(DataIntegrityViolationException.class,
				() -> jdbcTemplate.update(insert, productId, "CAD"));
		assertThrows(DataIntegrityViolationException.class,
				() -> jdbcTemplate.update(insert, productId, null));
	}

	@Test
	void detectsFiniteOverlap() {
		long productId = createProductWithFinitePrice();

		assertTrue(priceAdapter.overlaps(
				productId, LocalDate.of(2024, 3, 1), LocalDate.of(2024, 4, 1)));
	}

	@Test
	void detectsOverlapWhenIntervalsShareInclusiveBoundary() {
		long productId = createProductWithFinitePrice();

		assertTrue(priceAdapter.overlaps(productId, JUNE_THIRTIETH, DECEMBER_THIRTY_FIRST));
	}

	@Test
	void allowsGapWithoutOverlap() {
		long productId = createProductWithFinitePrice();

		assertFalse(priceAdapter.overlaps(productId, JULY_FIRST, DECEMBER_THIRTY_FIRST));
	}

	@Test
	void detectsOverlapForOpenEndedCandidateWithoutBindingNullEndDate() {
		long productId = createProductWithFinitePrice();

		assertTrue(priceAdapter.overlaps(productId, JUNE_THIRTIETH, null));
		assertFalse(priceAdapter.overlaps(productId, JULY_FIRST, null));
	}

	@Test
	void returnsHistoryOrderedByInitDateThenId() {
		long productId = createProduct("Product").getId();
		Price later = priceAdapter.save(Price.create(
				productId, money("20.00"), CurrencyCode.USD, JULY_FIRST, DECEMBER_THIRTY_FIRST));
		Price earlier = priceAdapter.save(Price.create(
				productId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH));

		List<Price> history = priceAdapter.findHistoryByProductId(productId);

		assertEquals(List.of(earlier.getId(), later.getId()),
				history.stream().map(Price::getId).toList());
	}

	@Test
	void returnsEmptyHistoryForProductWithoutPrices() {
		long productId = createProduct("Product").getId();

		assertTrue(priceAdapter.findHistoryByProductId(productId).isEmpty());
	}

	@Test
	void overlappingIntervalsForSameProductAreTranslatedToPriceOverlap() {
		long productId = createProductWithFinitePrice();

		assertThrows(PriceOverlapException.class, () -> priceAdapter.save(Price.create(
				productId, money("20.00"), CurrencyCode.USD, JUNE_THIRTIETH, DECEMBER_THIRTY_FIRST)));
		assertEquals(1, countPrices(productId));
	}

	@Test
	void nonOverlappingIntervalsForSameProductCanBePersisted() {
		long productId = createProductWithFinitePrice();

		priceAdapter.save(Price.create(productId, money("20.00"), CurrencyCode.EUR, JULY_FIRST, DECEMBER_THIRTY_FIRST));

		assertEquals(2, countPrices(productId));
	}

	@Test
	void equivalentIntervalsForDifferentProductsCanBePersisted() {
		long firstProductId = createProduct("First").getId();
		long secondProductId = createProduct("Second").getId();

		priceAdapter.save(Price.create(firstProductId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH));
		priceAdapter.save(Price.create(secondProductId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH));

		assertEquals(1, countPrices(firstProductId));
		assertEquals(1, countPrices(secondProductId));
	}

	@Test
	void nonOverlapIntegrityViolationsAreNotMislabeled() {
		Price orphanPrice = Price.create(Long.MAX_VALUE, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH);

		assertThrows(DataIntegrityViolationException.class, () -> priceAdapter.save(orphanPrice));
	}

	@Test
	void concurrentOverlappingInsertsYieldOneSuccessAndOneOverlap() throws Exception {
		long productId = createProduct("Product").getId();
		Price first = Price.create(productId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH);
		Price second = Price.create(productId, money("20.00"), CurrencyCode.EUR, JUNE_THIRTIETH, DECEMBER_THIRTY_FIRST);

		List<InsertOutcome> outcomes = insertConcurrently(first, second);

		assertEquals(1, outcomes.stream().filter(outcome -> outcome == InsertOutcome.SAVED).count());
		assertEquals(1, outcomes.stream().filter(outcome -> outcome == InsertOutcome.OVERLAP).count());
		assertEquals(1, countPrices(productId));
	}

	@Test
	void concurrentNonOverlappingInsertsBothSucceed() throws Exception {
		long productId = createProduct("Product").getId();
		Price first = Price.create(productId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH);
		Price second = Price.create(productId, money("20.00"), CurrencyCode.EUR, JULY_FIRST, DECEMBER_THIRTY_FIRST);

		List<InsertOutcome> outcomes = insertConcurrently(first, second);

		assertEquals(List.of(InsertOutcome.SAVED, InsertOutcome.SAVED), outcomes);
		assertEquals(2, countPrices(productId));
	}

	private List<InsertOutcome> insertConcurrently(Price first, Price second) throws Exception {
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<InsertOutcome> firstResult = executor.submit(() -> insertAfterBarrier(first, barrier));
			Future<InsertOutcome> secondResult = executor.submit(() -> insertAfterBarrier(second, barrier));
			return List.of(
					firstResult.get(20, TimeUnit.SECONDS),
					secondResult.get(20, TimeUnit.SECONDS));
		}
		finally {
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private InsertOutcome insertAfterBarrier(Price price, CyclicBarrier barrier) throws Exception {
		barrier.await(10, TimeUnit.SECONDS);
		try {
			priceAdapter.save(price);
			return InsertOutcome.SAVED;
		}
		catch (PriceOverlapException exception) {
			return InsertOutcome.OVERLAP;
		}
	}

	private long createProductWithFinitePrice() {
		long productId = createProduct("Product").getId();
		priceAdapter.save(Price.create(productId, money("10.00"), CurrencyCode.EUR, JANUARY_FIRST, JUNE_THIRTIETH));
		return productId;
	}

	private Product createProduct(String name) {
		return productAdapter.save(Product.create(name, null));
	}

	private int countPrices(long productId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM prices WHERE product_id = ?", Integer.class, productId);
	}

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private enum InsertOutcome {
		SAVED,
		OVERLAP
	}

}

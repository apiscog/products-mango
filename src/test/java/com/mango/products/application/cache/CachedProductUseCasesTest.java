package com.mango.products.application.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.mango.products.application.exception.PriceNotFoundException;
import com.mango.products.application.exception.PriceOverlapException;
import com.mango.products.application.port.in.ProductUseCases;
import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.ConvertedPriceResult;
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.service.ProductApplicationService;
import com.mango.products.application.service.CurrencyConversionService;
import com.mango.products.domain.exception.DomainValidationException;
import com.mango.products.domain.model.CurrencyCode;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CachedProductUseCasesTest.TestConfiguration.class)
class CachedProductUseCasesTest {

	private static final LocalDate DATE = LocalDate.of(2024, 4, 15);

	@Autowired
	@Qualifier("cachedProductUseCases")
	private ProductUseCases useCases;

	@Autowired
	private ProductApplicationService delegate;

	@Autowired
	private CurrencyConversionService currencyConversionService;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private InMemoryVersionStore versionStore;

	@BeforeEach
	void resetState() {
		reset(delegate, currencyConversionService);
		cacheManager.getCache(ProductCacheNames.CURRENT_PRICE).clear();
		cacheManager.getCache(ProductCacheNames.PRICE_HISTORY).clear();
		versionStore.clear();
	}

	@Test
	void secondCurrentPriceQueryUsesCacheInsteadOfDelegate() {
		CurrentPriceResult expected = new CurrentPriceResult(new BigDecimal("99.99"), CurrencyCode.EUR);
		when(delegate.getPriceAtDate(1L, DATE)).thenReturn(expected);

		assertEquals(expected, useCases.getPriceAtDate(1L, DATE));
		assertEquals(expected, useCases.getPriceAtDate(1L, DATE));

		verify(delegate, times(1)).getPriceAtDate(1L, DATE);
	}

	@Test
	void currentPriceKeysDifferByProductAndDate() {
		LocalDate anotherDate = DATE.plusDays(1);
		when(delegate.getPriceAtDate(1L, DATE)).thenReturn(new CurrentPriceResult(BigDecimal.ONE, CurrencyCode.EUR));
		when(delegate.getPriceAtDate(1L, anotherDate)).thenReturn(new CurrentPriceResult(BigDecimal.TWO, CurrencyCode.EUR));
		when(delegate.getPriceAtDate(2L, DATE)).thenReturn(new CurrentPriceResult(BigDecimal.TEN, CurrencyCode.USD));

		useCases.getPriceAtDate(1L, DATE);
		useCases.getPriceAtDate(1L, anotherDate);
		useCases.getPriceAtDate(2L, DATE);

		Map<?, ?> entries = (Map<?, ?>) cacheManager.getCache(ProductCacheNames.CURRENT_PRICE).getNativeCache();
		assertEquals(3, entries.size());
		assertTrue(entries.containsKey("1::v0::2024-04-15"));
		assertTrue(entries.containsKey("1::v0::2024-04-16"));
		assertTrue(entries.containsKey("2::v0::2024-04-15"));
	}

	@Test
	void secondHistoryQueryUsesCacheInsteadOfDelegate() {
		ProductHistoryResult expected = history("99.99");
		when(delegate.getPriceHistory(1L)).thenReturn(expected);

		assertEquals(expected, useCases.getPriceHistory(1L));
		assertEquals(expected, useCases.getPriceHistory(1L));

		verify(delegate, times(1)).getPriceHistory(1L);
	}

	@Test
	void successfulAddInvalidatesOnlyThatProductsCurrentPricesAndHistory() {
		when(delegate.getPriceAtDate(1L, DATE)).thenReturn(new CurrentPriceResult(BigDecimal.ONE, CurrencyCode.EUR));
		when(delegate.getPriceAtDate(2L, DATE)).thenReturn(new CurrentPriceResult(BigDecimal.TWO, CurrencyCode.USD));
		when(delegate.getPriceHistory(1L)).thenReturn(history("1.00"));
		AddPriceCommand command = new AddPriceCommand(
				new BigDecimal("20.00"), CurrencyCode.USD, LocalDate.of(2025, 1, 1), null);
		when(delegate.addPrice(1L, command)).thenReturn(new PriceResult(
				command.value(), command.currency(), command.initDate(), command.endDate()));

		useCases.getPriceAtDate(1L, DATE);
		useCases.getPriceAtDate(2L, DATE);
		useCases.getPriceHistory(1L);
		useCases.addPrice(1L, command);
		useCases.getPriceAtDate(1L, DATE);
		useCases.getPriceAtDate(2L, DATE);
		useCases.getPriceHistory(1L);

		assertEquals(1L, versionStore.getStoredVersion(1L));
		assertEquals(0L, versionStore.getStoredVersion(2L));
		verify(delegate, times(2)).getPriceAtDate(1L, DATE);
		verify(delegate, times(1)).getPriceAtDate(2L, DATE);
		verify(delegate, times(2)).getPriceHistory(1L);
	}

	@Test
	void failedAddDoesNotInvalidateCachedQueries() {
		when(delegate.getPriceAtDate(1L, DATE)).thenReturn(new CurrentPriceResult(BigDecimal.ONE, CurrencyCode.EUR));
		AddPriceCommand command = new AddPriceCommand(
				new BigDecimal("20.00"), CurrencyCode.EUR,
				LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 30));
		when(delegate.addPrice(1L, command)).thenThrow(
				new PriceOverlapException(1L, command.initDate(), command.endDate()));

		useCases.getPriceAtDate(1L, DATE);
		assertThrows(PriceOverlapException.class, () -> useCases.addPrice(1L, command));
		useCases.getPriceAtDate(1L, DATE);

		assertEquals(0L, versionStore.getStoredVersion(1L));
		verify(delegate, times(1)).getPriceAtDate(1L, DATE);
	}

	@Test
	void queryExceptionsAreNotCached() {
		when(delegate.getPriceAtDate(1L, DATE)).thenThrow(new PriceNotFoundException(1L, DATE));

		assertThrows(PriceNotFoundException.class, () -> useCases.getPriceAtDate(1L, DATE));
		assertThrows(PriceNotFoundException.class, () -> useCases.getPriceAtDate(1L, DATE));

		verify(delegate, times(2)).getPriceAtDate(1L, DATE);
	}

	@Test
	void convertedQueriesReuseCachedOriginalButAreCalculatedOnEveryRequest() {
		CurrentPriceResult original = new CurrentPriceResult(new BigDecimal("99.99"), CurrencyCode.EUR);
		ConvertedPriceResult converted = new ConvertedPriceResult(
				new BigDecimal("106.74"), CurrencyCode.USD,
				original.value(), original.currency(), new BigDecimal("1.0675"), DATE);
		when(delegate.getPriceAtDate(1L, DATE)).thenReturn(original);
		when(currencyConversionService.convert(original, CurrencyCode.USD, DATE)).thenReturn(converted);

		assertEquals(converted, useCases.getPriceAtDate(1L, DATE, CurrencyCode.USD));
		assertEquals(converted, useCases.getPriceAtDate(1L, DATE, CurrencyCode.USD));

		verify(delegate, times(1)).getPriceAtDate(1L, DATE);
		verify(currencyConversionService, times(2)).convert(original, CurrencyCode.USD, DATE);
	}

	@Test
	void invalidIdentifiersAndNullDatesDoNotReadCacheVersion() {
		assertThrows(DomainValidationException.class, () -> useCases.getPriceAtDate(0L, DATE));
		assertThrows(DomainValidationException.class, () -> useCases.getPriceAtDate(1L, null));
		assertThrows(DomainValidationException.class, () -> useCases.getPriceHistory(-1L));

		assertEquals(0, versionStore.readCount());
		verifyNoInteractions(delegate);
	}

	private static ProductHistoryResult history(String value) {
		return new ProductHistoryResult(
				"Product",
				"Description",
				List.of(new PriceResult(
						new BigDecimal(value), CurrencyCode.EUR, LocalDate.of(2024, 1, 1), null)));
	}

	@Configuration
	@EnableCaching
	static class TestConfiguration {

		@Bean
		ProductApplicationService delegate() {
			return mock(ProductApplicationService.class);
		}

		@Bean
		CurrencyConversionService currencyConversionService() {
			return mock(CurrencyConversionService.class);
		}

		@Bean
		InMemoryVersionStore versionStore() {
			return new InMemoryVersionStore();
		}

		@Bean
		ProductCacheKeyService productCacheKeyService(InMemoryVersionStore versionStore) {
			return new ProductCacheKeyService(versionStore);
		}

		@Bean
		ProductCacheInvalidator cacheInvalidator(InMemoryVersionStore versionStore) {
			return new ProductCacheInvalidator(versionStore);
		}

		@Bean
		CachedPriceQueryService cachedPriceQueryService(ProductApplicationService delegate) {
			return new CachedPriceQueryService(delegate);
		}

		@Bean
		CachedProductUseCases cachedProductUseCases(
				ProductApplicationService delegate,
				ProductCacheInvalidator cacheInvalidator,
				CachedPriceQueryService cachedPriceQueryService,
				CurrencyConversionService currencyConversionService) {
			return new CachedProductUseCases(
					delegate, cacheInvalidator, cachedPriceQueryService, currencyConversionService);
		}

		@Bean
		CacheManager cacheManager() {
			return new ConcurrentMapCacheManager(
					ProductCacheNames.CURRENT_PRICE,
					ProductCacheNames.PRICE_HISTORY);
		}
	}

	static final class InMemoryVersionStore implements ProductCacheVersionStore {

		private final Map<Long, Long> versions = new ConcurrentHashMap<>();
		private int reads;

		@Override
		public long getVersion(long productId) {
			reads++;
			return getStoredVersion(productId);
		}

		@Override
		public void incrementVersion(long productId) {
			versions.merge(productId, 1L, Long::sum);
		}

		long getStoredVersion(long productId) {
			return versions.getOrDefault(productId, 0L);
		}

		int readCount() {
			return reads;
		}

		void clear() {
			versions.clear();
			reads = 0;
		}
	}

}

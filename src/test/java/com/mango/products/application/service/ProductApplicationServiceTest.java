package com.mango.products.application.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mango.products.application.exception.PriceNotFoundException;
import com.mango.products.application.exception.PriceOverlapException;
import com.mango.products.application.exception.ProductNotFoundException;
import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.ConvertedPriceResult;
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;
import com.mango.products.application.port.out.PriceRepository;
import com.mango.products.application.port.out.ProductRepository;
import com.mango.products.domain.exception.DomainValidationException;
import com.mango.products.domain.model.Price;
import com.mango.products.domain.model.Product;
import com.mango.products.domain.model.CurrencyCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductApplicationServiceTest {

	private static final long PRODUCT_ID = 10L;
	private static final LocalDate INIT_DATE = LocalDate.of(2024, 1, 1);
	private static final LocalDate END_DATE = LocalDate.of(2024, 6, 30);
	private static final BigDecimal VALUE = new BigDecimal("99.99");

	@Mock
	private ProductRepository productRepository;

	@Mock
	private PriceRepository priceRepository;

	@Mock
	private CurrencyConversionService currencyConversionService;

	private ProductApplicationService service;

	@BeforeEach
	void setUp() {
		service = new ProductApplicationService(
				productRepository, priceRepository, currencyConversionService);
	}

	@Test
	void createProductBuildsSavesAndReturnsExpectedResult() {
		CreateProductCommand command = new CreateProductCommand("Running shoes", "Limited edition");
		when(productRepository.save(any(Product.class)))
				.thenReturn(Product.reconstitute(PRODUCT_ID, command.name(), command.description()));

		ProductResult result = service.createProduct(command);

		ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
		verify(productRepository).save(captor.capture());
		assertNull(captor.getValue().getId());
		assertEquals(command.name(), captor.getValue().getName());
		assertEquals(new ProductResult(PRODUCT_ID, command.name(), command.description()), result);
		verifyNoInteractions(priceRepository);
	}

	@Test
	void createProductPropagatesDomainNameValidation() {
		CreateProductCommand command = new CreateProductCommand("   ", "Description");

		assertThrows(DomainValidationException.class, () -> service.createProduct(command));

		verifyNoInteractions(productRepository, priceRepository);
	}

	@Test
	void addPriceChecksProductAndOverlapWithoutLoadingHistoryThenSaves() {
		AddPriceCommand command = validPriceCommand();
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
		when(priceRepository.overlaps(PRODUCT_ID, INIT_DATE, END_DATE)).thenReturn(false);
		when(priceRepository.save(any(Price.class)))
				.thenReturn(Price.reconstitute(
						1L, PRODUCT_ID, VALUE, CurrencyCode.EUR, INIT_DATE, END_DATE));

		PriceResult result = service.addPrice(PRODUCT_ID, command);

		assertEquals(new PriceResult(VALUE, CurrencyCode.EUR, INIT_DATE, END_DATE), result);
		verify(productRepository).existsById(PRODUCT_ID);
		verify(priceRepository, times(1)).overlaps(PRODUCT_ID, INIT_DATE, END_DATE);
		verify(priceRepository).save(any(Price.class));
		verify(priceRepository, never()).findHistoryByProductId(anyLong());
	}

	@Test
	void addPriceFailsWhenProductDoesNotExist() {
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);

		assertThrows(ProductNotFoundException.class, () -> service.addPrice(PRODUCT_ID, validPriceCommand()));

		verify(productRepository).existsById(PRODUCT_ID);
		verifyNoInteractions(priceRepository);
	}

	@Test
	void addPriceFailsOnOverlapAndDoesNotSave() {
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
		when(priceRepository.overlaps(PRODUCT_ID, INIT_DATE, END_DATE)).thenReturn(true);

		assertThrows(PriceOverlapException.class, () -> service.addPrice(PRODUCT_ID, validPriceCommand()));

		verify(priceRepository, times(1)).overlaps(PRODUCT_ID, INIT_DATE, END_DATE);
		verify(priceRepository, never()).save(any(Price.class));
		verify(priceRepository, never()).findHistoryByProductId(anyLong());
	}

	@Test
	void addPricePropagatesMonetaryValidationBeforeOverlapCheck() {
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
		AddPriceCommand command = new AddPriceCommand(
				BigDecimal.ZERO, CurrencyCode.EUR, INIT_DATE, END_DATE);

		assertThrows(DomainValidationException.class, () -> service.addPrice(PRODUCT_ID, command));

		verify(productRepository).existsById(PRODUCT_ID);
		verifyNoInteractions(priceRepository);
	}

	@Test
	void addPricePropagatesTemporalValidationBeforeOverlapCheck() {
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);
		AddPriceCommand command = new AddPriceCommand(
				VALUE, CurrencyCode.EUR, INIT_DATE, INIT_DATE);

		assertThrows(DomainValidationException.class, () -> service.addPrice(PRODUCT_ID, command));

		verify(productRepository).existsById(PRODUCT_ID);
		verifyNoInteractions(priceRepository);
	}

	@Test
	void addPriceRejectsNonPositiveProductIdWithoutUsingRepositories() {
		assertThrows(DomainValidationException.class, () -> service.addPrice(0, validPriceCommand()));
		assertThrows(DomainValidationException.class, () -> service.addPrice(-1, validPriceCommand()));

		verifyNoInteractions(productRepository, priceRepository);
	}

	@Test
	void getPriceAtDateReturnsCurrentValueWithoutCheckingProductExistence() {
		Price price = Price.reconstitute(
				1L, PRODUCT_ID, VALUE, CurrencyCode.EUR, INIT_DATE, END_DATE);
		when(priceRepository.findAtDate(PRODUCT_ID, INIT_DATE)).thenReturn(Optional.of(price));

		CurrentPriceResult result = service.getPriceAtDate(PRODUCT_ID, INIT_DATE);

		assertEquals(new CurrentPriceResult(VALUE, CurrencyCode.EUR), result);
		verify(priceRepository).findAtDate(PRODUCT_ID, INIT_DATE);
		verify(productRepository, never()).existsById(PRODUCT_ID);
	}

	@Test
	void getPriceAtDateFailsWhenProductDoesNotExist() {
		when(priceRepository.findAtDate(PRODUCT_ID, INIT_DATE)).thenReturn(Optional.empty());
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(false);

		assertThrows(ProductNotFoundException.class, () -> service.getPriceAtDate(PRODUCT_ID, INIT_DATE));

		verify(productRepository).existsById(PRODUCT_ID);
	}

	@Test
	void getPriceAtDateFailsWhenProductHasNoPriceForDate() {
		when(priceRepository.findAtDate(PRODUCT_ID, INIT_DATE)).thenReturn(Optional.empty());
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);

		assertThrows(PriceNotFoundException.class, () -> service.getPriceAtDate(PRODUCT_ID, INIT_DATE));

		verify(productRepository).existsById(PRODUCT_ID);
	}

	@Test
	void getPriceAtDateWithTargetUsesSameDateAndConversionService() {
		Price price = Price.reconstitute(
				1L, PRODUCT_ID, VALUE, CurrencyCode.EUR, INIT_DATE, END_DATE);
		ConvertedPriceResult converted = new ConvertedPriceResult(
				new BigDecimal("106.74"), CurrencyCode.USD, VALUE, CurrencyCode.EUR,
				new BigDecimal("1.0675"), INIT_DATE);
		when(priceRepository.findAtDate(PRODUCT_ID, INIT_DATE)).thenReturn(Optional.of(price));
		when(currencyConversionService.convert(price, CurrencyCode.USD, INIT_DATE))
				.thenReturn(converted);

		assertEquals(converted, service.getPriceAtDate(PRODUCT_ID, INIT_DATE, CurrencyCode.USD));
		verify(currencyConversionService).convert(price, CurrencyCode.USD, INIT_DATE);
	}

	@Test
	void getPriceAtDateWithTargetDoesNotConvertWhenPriceIsMissing() {
		when(priceRepository.findAtDate(PRODUCT_ID, INIT_DATE)).thenReturn(Optional.empty());
		when(productRepository.existsById(PRODUCT_ID)).thenReturn(true);

		assertThrows(
				PriceNotFoundException.class,
				() -> service.getPriceAtDate(PRODUCT_ID, INIT_DATE, CurrencyCode.USD));
		verifyNoInteractions(currencyConversionService);
	}

	@Test
	void getPriceAtDateRejectsNullDateWithoutUsingRepositories() {
		assertThrows(DomainValidationException.class, () -> service.getPriceAtDate(PRODUCT_ID, null));

		verifyNoInteractions(productRepository, priceRepository);
	}

	@Test
	void getPriceAtDateRejectsNonPositiveProductIdWithoutUsingRepositories() {
		assertThrows(DomainValidationException.class, () -> service.getPriceAtDate(0, INIT_DATE));
		assertThrows(DomainValidationException.class, () -> service.getPriceAtDate(-1, INIT_DATE));

		verifyNoInteractions(productRepository, priceRepository);
	}

	@Test
	void getPriceHistoryReturnsProductAndPreservesRepositoryOrder() {
		Product product = Product.reconstitute(PRODUCT_ID, "Running shoes", "Limited edition");
		Price later = Price.reconstitute(2L, PRODUCT_ID, new BigDecimal("120.00"), CurrencyCode.USD,
				LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30));
		Price earlier = Price.reconstitute(
				1L, PRODUCT_ID, VALUE, CurrencyCode.EUR, INIT_DATE, END_DATE);
		when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(priceRepository.findHistoryByProductId(PRODUCT_ID)).thenReturn(List.of(later, earlier));

		ProductHistoryResult result = service.getPriceHistory(PRODUCT_ID);

		assertEquals("Running shoes", result.name());
		assertEquals("Limited edition", result.description());
		assertEquals(later.getInitDate(), result.prices().get(0).initDate());
		assertEquals(earlier.getInitDate(), result.prices().get(1).initDate());
		verify(priceRepository).findHistoryByProductId(PRODUCT_ID);
	}

	@Test
	void getPriceHistoryReturnsEmptyListWhenProductHasNoPrices() {
		Product product = Product.reconstitute(PRODUCT_ID, "Product", null);
		when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(priceRepository.findHistoryByProductId(PRODUCT_ID)).thenReturn(List.of());

		ProductHistoryResult result = service.getPriceHistory(PRODUCT_ID);

		assertEquals(List.of(), result.prices());
	}

	@Test
	void getPriceHistoryFailsWhenProductDoesNotExist() {
		when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

		assertThrows(ProductNotFoundException.class, () -> service.getPriceHistory(PRODUCT_ID));

		verifyNoInteractions(priceRepository);
	}

	@Test
	void getPriceHistoryRejectsNonPositiveProductIdWithoutUsingRepositories() {
		assertThrows(DomainValidationException.class, () -> service.getPriceHistory(0));
		assertThrows(DomainValidationException.class, () -> service.getPriceHistory(-1));

		verifyNoInteractions(productRepository, priceRepository);
	}

	@Test
	void getPriceHistoryReturnsAnImmutableDefensiveList() {
		Product product = Product.reconstitute(PRODUCT_ID, "Product", null);
		Price price = Price.reconstitute(
				1L, PRODUCT_ID, VALUE, CurrencyCode.EUR, INIT_DATE, END_DATE);
		List<Price> repositoryPrices = new ArrayList<>(List.of(price));
		when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(priceRepository.findHistoryByProductId(PRODUCT_ID)).thenReturn(repositoryPrices);

		ProductHistoryResult result = service.getPriceHistory(PRODUCT_ID);
		repositoryPrices.clear();

		assertEquals(1, result.prices().size());
		assertThrows(UnsupportedOperationException.class, () -> result.prices().add(result.prices().get(0)));
	}

	private static AddPriceCommand validPriceCommand() {
		return new AddPriceCommand(VALUE, CurrencyCode.EUR, INIT_DATE, END_DATE);
	}

}

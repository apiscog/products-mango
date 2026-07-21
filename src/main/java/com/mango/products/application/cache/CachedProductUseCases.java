package com.mango.products.application.cache;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.mango.products.application.port.in.ProductUseCases;
import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.ConvertedPriceResult;
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;
import com.mango.products.application.service.ProductApplicationService;
import com.mango.products.application.service.CurrencyConversionService;
import com.mango.products.domain.model.CurrencyCode;

@Primary
@Service
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class CachedProductUseCases implements ProductUseCases {

	private final ProductApplicationService delegate;
	private final ProductCacheInvalidator cacheInvalidator;
	private final CachedPriceQueryService priceQueryService;
	private final CurrencyConversionService currencyConversionService;

	public CachedProductUseCases(
			ProductApplicationService delegate,
			ProductCacheInvalidator cacheInvalidator,
			CachedPriceQueryService priceQueryService,
			CurrencyConversionService currencyConversionService) {
		this.delegate = delegate;
		this.cacheInvalidator = cacheInvalidator;
		this.priceQueryService = priceQueryService;
		this.currencyConversionService = currencyConversionService;
	}

	@Override
	public ProductResult createProduct(CreateProductCommand command) {
		return delegate.createProduct(command);
	}

	@Override
	public PriceResult addPrice(long productId, AddPriceCommand command) {
		PriceResult result = delegate.addPrice(productId, command);
		cacheInvalidator.invalidateProduct(productId);
		return result;
	}

	@Override
	public CurrentPriceResult getPriceAtDate(long productId, LocalDate date) {
		return priceQueryService.getOriginalPriceAtDate(productId, date);
	}

	@Override
	public ConvertedPriceResult getPriceAtDate(
			long productId,
			LocalDate date,
			CurrencyCode targetCurrency) {
		CurrentPriceResult original = priceQueryService.getOriginalPriceAtDate(productId, date);
		return currencyConversionService.convert(original, targetCurrency, date);
	}

	@Override
	@Cacheable(
			cacheNames = ProductCacheNames.PRICE_HISTORY,
			key = "@productCacheKeyService.priceHistoryKey(#productId)",
			unless = "#result == null")
	public ProductHistoryResult getPriceHistory(long productId) {
		return delegate.getPriceHistory(productId);
	}

}

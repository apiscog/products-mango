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
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;
import com.mango.products.application.service.ProductApplicationService;

@Primary
@Service
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class CachedProductUseCases implements ProductUseCases {

	private final ProductApplicationService delegate;
	private final ProductCacheInvalidator cacheInvalidator;

	public CachedProductUseCases(
			ProductApplicationService delegate,
			ProductCacheInvalidator cacheInvalidator) {
		this.delegate = delegate;
		this.cacheInvalidator = cacheInvalidator;
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
	@Cacheable(
			cacheNames = ProductCacheNames.CURRENT_PRICE,
			key = "@productCacheKeyService.currentPriceKey(#productId, #date)",
			unless = "#result == null")
	public CurrentPriceResult getPriceAtDate(long productId, LocalDate date) {
		return delegate.getPriceAtDate(productId, date);
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

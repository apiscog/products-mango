package com.mango.products.application.cache;

import java.time.LocalDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.service.ProductApplicationService;

@Service
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "redis", matchIfMissing = true)
public class CachedPriceQueryService {

	private final ProductApplicationService delegate;

	public CachedPriceQueryService(ProductApplicationService delegate) {
		this.delegate = delegate;
	}

	@Cacheable(
			cacheNames = ProductCacheNames.CURRENT_PRICE,
			key = "@productCacheKeyService.currentPriceKey(#productId, #date)",
			unless = "#result == null")
	public CurrentPriceResult getOriginalPriceAtDate(long productId, LocalDate date) {
		return delegate.getPriceAtDate(productId, date);
	}
}

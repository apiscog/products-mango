package com.mango.products.adapter.out.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "products.cache")
public record ProductCacheProperties(Duration currentPriceTtl, Duration priceHistoryTtl) {
}

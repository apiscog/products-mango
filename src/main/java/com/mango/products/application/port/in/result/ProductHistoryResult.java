package com.mango.products.application.port.in.result;

import java.util.List;

public record ProductHistoryResult(String name, String description, List<PriceResult> prices) {

	public ProductHistoryResult {
		prices = List.copyOf(prices);
	}

}

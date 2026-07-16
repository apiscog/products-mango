package com.mango.products.adapter.in.web.dto.response;

import java.util.List;

public record ProductHistoryResponse(
        String name,
        String description,
        List<PriceHistoryItemResponse> prices
) {
    public ProductHistoryResponse {
        prices = List.copyOf(prices);
    }
}

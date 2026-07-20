package com.mango.products.adapter.in.web.dto.response;

import java.math.BigDecimal;

import com.mango.products.domain.model.CurrencyCode;

public record CurrentPriceResponse(BigDecimal value, CurrencyCode currency) {
}

package com.mango.products.application.port.in.result;

import java.math.BigDecimal;

import com.mango.products.domain.model.CurrencyCode;

public record CurrentPriceResult(BigDecimal value, CurrencyCode currency) {
}

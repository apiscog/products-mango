package com.mango.products.adapter.in.web.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceHistoryItemResponse(BigDecimal value, LocalDate initDate, LocalDate endDate) {
}

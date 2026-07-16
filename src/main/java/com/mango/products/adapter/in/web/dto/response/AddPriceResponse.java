package com.mango.products.adapter.in.web.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AddPriceResponse(BigDecimal value, LocalDate initDate, LocalDate endDate) {
}

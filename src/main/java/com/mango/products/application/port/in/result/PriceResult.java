package com.mango.products.application.port.in.result;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceResult(BigDecimal value, LocalDate initDate, LocalDate endDate) {
}

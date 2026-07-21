package com.mango.products.application.port.in.command;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.domain.model.CurrencyCode;

public record AddPriceCommand(BigDecimal value, CurrencyCode currency, LocalDate initDate, LocalDate endDate) {
}

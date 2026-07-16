package com.mango.products.application.port.in.command;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AddPriceCommand(BigDecimal value, LocalDate initDate, LocalDate endDate) {
}

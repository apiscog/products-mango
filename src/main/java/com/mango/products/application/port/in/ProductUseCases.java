package com.mango.products.application.port.in;

import java.time.LocalDate;

import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;

public interface ProductUseCases {

	ProductResult createProduct(CreateProductCommand command);

	PriceResult addPrice(long productId, AddPriceCommand command);

	CurrentPriceResult getPriceAtDate(long productId, LocalDate date);

	ProductHistoryResult getPriceHistory(long productId);

}

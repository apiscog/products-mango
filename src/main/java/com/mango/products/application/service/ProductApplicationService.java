package com.mango.products.application.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mango.products.application.exception.PriceNotFoundException;
import com.mango.products.application.exception.PriceOverlapException;
import com.mango.products.application.exception.ProductNotFoundException;
import com.mango.products.application.port.in.ProductUseCases;
import com.mango.products.application.port.in.command.AddPriceCommand;
import com.mango.products.application.port.in.command.CreateProductCommand;
import com.mango.products.application.port.in.result.CurrentPriceResult;
import com.mango.products.application.port.in.result.PriceResult;
import com.mango.products.application.port.in.result.ProductHistoryResult;
import com.mango.products.application.port.in.result.ProductResult;
import com.mango.products.application.port.out.PriceRepository;
import com.mango.products.application.port.out.ProductRepository;
import com.mango.products.domain.exception.DomainValidationException;
import com.mango.products.domain.model.Price;
import com.mango.products.domain.model.Product;

@Service
public class ProductApplicationService implements ProductUseCases {

	private final ProductRepository productRepository;
	private final PriceRepository priceRepository;

	public ProductApplicationService(ProductRepository productRepository, PriceRepository priceRepository) {
		this.productRepository = productRepository;
		this.priceRepository = priceRepository;
	}

	@Override
	@Transactional
	public ProductResult createProduct(CreateProductCommand command) {
		if (command == null) {
			throw new DomainValidationException("Create product command is required");
		}
		Product product = Product.create(command.name(), command.description());
		return toProductResult(productRepository.save(product));
	}

	@Override
	@Transactional
	public PriceResult addPrice(long productId, AddPriceCommand command) {
		validateProductId(productId);
		if (command == null) {
			throw new DomainValidationException("Add price command is required");
		}
		if (!productRepository.existsById(productId)) {
			throw new ProductNotFoundException(productId);
		}

		Price price = Price.create(productId, command.value(), command.initDate(), command.endDate());
		if (priceRepository.overlaps(productId, command.initDate(), command.endDate())) {
			throw new PriceOverlapException(productId, command.initDate(), command.endDate());
		}
		return toPriceResult(priceRepository.save(price));
	}

	@Override
	@Transactional(readOnly = true)
	public CurrentPriceResult getPriceAtDate(long productId, LocalDate date) {
		validateProductId(productId);
		if (date == null) {
			throw new DomainValidationException("Price date is required");
		}

		return priceRepository.findValueAtDate(productId, date)
				.map(CurrentPriceResult::new)
				.orElseGet(() -> priceNotFound(productId, date));
	}

	@Override
	@Transactional(readOnly = true)
	public ProductHistoryResult getPriceHistory(long productId) {
		validateProductId(productId);
		Product product = productRepository.findById(productId)
				.orElseThrow(() -> new ProductNotFoundException(productId));
		List<PriceResult> prices = priceRepository.findHistoryByProductId(productId).stream()
				.map(ProductApplicationService::toPriceResult)
				.toList();
		return new ProductHistoryResult(product.getName(), product.getDescription(), prices);
	}

	private CurrentPriceResult priceNotFound(long productId, LocalDate date) {
		if (!productRepository.existsById(productId)) {
			throw new ProductNotFoundException(productId);
		}
		throw new PriceNotFoundException(productId, date);
	}

	private static ProductResult toProductResult(Product product) {
		return new ProductResult(product.getId(), product.getName(), product.getDescription());
	}

	private static PriceResult toPriceResult(Price price) {
		return new PriceResult(price.getValue(), price.getInitDate(), price.getEndDate());
	}

	private static void validateProductId(long productId) {
		if (productId <= 0) {
			throw new DomainValidationException("Product id must be greater than zero");
		}
	}

}

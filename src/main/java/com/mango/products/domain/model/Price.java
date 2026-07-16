package com.mango.products.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mango.products.domain.exception.DomainValidationException;

public final class Price {

	private static final int MAX_SCALE = 2;

	private final Long id;
	private final Long productId;
	private final BigDecimal value;
	private final LocalDate initDate;
	private final LocalDate endDate;

	private Price(Long id, Long productId, BigDecimal value, LocalDate initDate, LocalDate endDate) {
		this.id = id;
		this.productId = validateProductId(productId);
		this.value = validateValue(value);
		this.initDate = validateInitDate(initDate);
		this.endDate = validateEndDate(initDate, endDate);
	}

	public static Price create(Long productId, BigDecimal value, LocalDate initDate, LocalDate endDate) {
		return new Price(null, productId, value, initDate, endDate);
	}

	public static Price reconstitute(
			Long id,
			Long productId,
			BigDecimal value,
			LocalDate initDate,
			LocalDate endDate) {
		if (id == null) {
			throw new DomainValidationException("Price id is required when reconstituting a persisted price");
		}
		return new Price(id, productId, value, initDate, endDate);
	}

	public boolean overlaps(Price other) {
		if (other == null) {
			throw new DomainValidationException("Price to compare is required");
		}
		boolean thisEndsBeforeOtherStarts = endDate != null && endDate.isBefore(other.initDate);
		boolean otherEndsBeforeThisStarts = other.endDate != null && other.endDate.isBefore(initDate);
		return !thisEndsBeforeOtherStarts && !otherEndsBeforeThisStarts;
	}

	public Long getId() {
		return id;
	}

	public Long getProductId() {
		return productId;
	}

	public BigDecimal getValue() {
		return value;
	}

	public LocalDate getInitDate() {
		return initDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	private static Long validateProductId(Long productId) {
		if (productId == null) {
			throw new DomainValidationException("Price product id is required");
		}
		return productId;
	}

	private static BigDecimal validateValue(BigDecimal value) {
		if (value == null) {
			throw new DomainValidationException("Price value is required");
		}
		if (value.compareTo(BigDecimal.ZERO) <= 0) {
			throw new DomainValidationException("Price value must be greater than zero");
		}
		if (value.scale() > MAX_SCALE) {
			throw new DomainValidationException("Price value must not have more than 2 decimal places");
		}
		return value;
	}

	private static LocalDate validateInitDate(LocalDate initDate) {
		if (initDate == null) {
			throw new DomainValidationException("Price init date is required");
		}
		return initDate;
	}

	private static LocalDate validateEndDate(LocalDate initDate, LocalDate endDate) {
		if (endDate != null && initDate != null && !initDate.isBefore(endDate)) {
			throw new DomainValidationException("Price init date must be before end date");
		}
		return endDate;
	}

}

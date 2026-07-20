package com.mango.products.adapter.out.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.mango.products.domain.model.CurrencyCode;

@Entity
@Table(name = "prices")
public class PriceJpaEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal value;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 3)
	private CurrencyCode currency;

	@Column(name = "init_date", nullable = false)
	private LocalDate initDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	protected PriceJpaEntity() {
	}

	public PriceJpaEntity(
			Long id,
			Long productId,
			BigDecimal value,
			CurrencyCode currency,
			LocalDate initDate,
			LocalDate endDate) {
		this.id = id;
		this.productId = productId;
		this.value = value;
		this.currency = currency;
		this.initDate = initDate;
		this.endDate = endDate;
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

	public CurrencyCode getCurrency() {
		return currency;
	}

	public LocalDate getInitDate() {
		return initDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

}

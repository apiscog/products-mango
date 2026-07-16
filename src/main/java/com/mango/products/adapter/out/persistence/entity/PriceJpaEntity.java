package com.mango.products.adapter.out.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

	@Column(name = "init_date", nullable = false)
	private LocalDate initDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	protected PriceJpaEntity() {
	}

	public PriceJpaEntity(Long id, Long productId, BigDecimal value, LocalDate initDate, LocalDate endDate) {
		this.id = id;
		this.productId = productId;
		this.value = value;
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

	public LocalDate getInitDate() {
		return initDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

}

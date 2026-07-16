package com.mango.products.adapter.out.persistence.adapter;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import com.mango.products.adapter.out.persistence.mapper.PricePersistenceMapper;
import com.mango.products.adapter.out.persistence.repository.SpringDataPriceRepository;
import com.mango.products.application.exception.PriceOverlapException;
import com.mango.products.application.port.out.PriceRepository;
import com.mango.products.domain.model.Price;

@Repository
public class PricePersistenceAdapter implements PriceRepository {

	private static final String EXCLUSION_VIOLATION_SQL_STATE = "23P01";
	private static final String OVERLAP_CONSTRAINT = "ex_prices_product_validity";

	private final SpringDataPriceRepository repository;

	public PricePersistenceAdapter(SpringDataPriceRepository repository) {
		this.repository = repository;
	}

	@Override
	public Price save(Price price) {
		try {
			return PricePersistenceMapper.toDomain(repository.saveAndFlush(PricePersistenceMapper.toEntity(price)));
		}
		catch (DataIntegrityViolationException exception) {
			if (isOverlapViolation(exception)) {
				throw new PriceOverlapException(price.getProductId(), price.getInitDate(), price.getEndDate());
			}
			throw exception;
		}
	}

	@Override
	public boolean overlaps(long productId, LocalDate initDate, LocalDate endDate) {
		if (endDate == null) {
			return repository.overlapsOpen(productId, initDate);
		}
		return repository.overlapsFinite(productId, initDate, endDate);
	}

	@Override
	public Optional<BigDecimal> findValueAtDate(long productId, LocalDate date) {
		return repository.findValueAtDate(productId, date);
	}

	@Override
	public List<Price> findHistoryByProductId(long productId) {
		return repository.findByProductIdOrderByInitDateAscIdAsc(productId).stream()
				.map(PricePersistenceMapper::toDomain)
				.toList();
	}

	private static boolean isOverlapViolation(Throwable failure) {
		boolean exclusionSqlState = false;
		boolean overlapConstraint = false;
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

		for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
			if (current instanceof SQLException sqlException
					&& EXCLUSION_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
				exclusionSqlState = true;
			}
			if (current instanceof ConstraintViolationException constraintViolation
					&& OVERLAP_CONSTRAINT.equals(constraintViolation.getConstraintName())) {
				overlapConstraint = true;
			}
			if (current.getMessage() != null && current.getMessage().contains(OVERLAP_CONSTRAINT)) {
				overlapConstraint = true;
			}
		}
		return exclusionSqlState && overlapConstraint;
	}

}

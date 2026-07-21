package com.mango.products.application.port.out;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.mango.products.domain.model.Price;

public interface PriceRepository {

	Price save(Price price);

	boolean overlaps(long productId, LocalDate initDate, LocalDate endDate);

	Optional<Price> findAtDate(long productId, LocalDate date);

	/**
	 * Returns prices ordered by init date ascending and id ascending.
	 */
	List<Price> findHistoryByProductId(long productId);

}

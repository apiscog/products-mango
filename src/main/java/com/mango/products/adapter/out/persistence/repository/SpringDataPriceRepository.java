package com.mango.products.adapter.out.persistence.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mango.products.adapter.out.persistence.entity.PriceJpaEntity;

public interface SpringDataPriceRepository extends JpaRepository<PriceJpaEntity, Long> {

	@Query(value = """
			SELECT p.value
			FROM prices p
			WHERE p.product_id = :productId
			  AND p.validity @> CAST(:date AS DATE)
			""", nativeQuery = true)
	Optional<BigDecimal> findValueAtDate(@Param("productId") long productId, @Param("date") LocalDate date);

	@Query(value = """
			SELECT EXISTS (
			    SELECT 1
			    FROM prices p
			    WHERE p.product_id = :productId
			      AND p.validity && daterange(
			          CAST(:initDate AS DATE),
			          CAST(:endDate AS DATE) + 1,
			          '[)'
			      )
			)
			""", nativeQuery = true)
	boolean overlapsFinite(
			@Param("productId") long productId,
			@Param("initDate") LocalDate initDate,
			@Param("endDate") LocalDate endDate);

	@Query(value = """
			SELECT EXISTS (
			    SELECT 1
			    FROM prices p
			    WHERE p.product_id = :productId
			      AND p.validity && daterange(
			          CAST(:initDate AS DATE),
			          NULL,
			          '[)'
			      )
			)
			""", nativeQuery = true)
	boolean overlapsOpen(@Param("productId") long productId, @Param("initDate") LocalDate initDate);

	List<PriceJpaEntity> findByProductIdOrderByInitDateAscIdAsc(long productId);

}

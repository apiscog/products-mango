package com.mango.products.adapter.out.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mango.products.adapter.out.persistence.entity.ProductJpaEntity;

public interface SpringDataProductRepository extends JpaRepository<ProductJpaEntity, Long> {
}

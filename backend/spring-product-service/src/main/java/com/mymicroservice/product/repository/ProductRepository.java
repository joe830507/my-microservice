package com.mymicroservice.product.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    boolean existsByIdAndStatus(UUID id, ProductStatus status);

    Optional<Product> findBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);
}
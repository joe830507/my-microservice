package com.mymicroservice.product.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    int INDEX_BATCH_SIZE = 500;

    boolean existsByIdAndStatus(UUID id, ProductStatus status);

    Optional<Product> findBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, UUID id);

    List<Product> findTop500ByOrderByIdAsc();

    List<Product> findTop500ByIdGreaterThanOrderByIdAsc(UUID id);

    List<Product> findTop500ByUpdatedAtGreaterThanEqualOrderByIdAsc(Instant updatedAt);

    List<Product> findTop500ByUpdatedAtGreaterThanEqualAndIdGreaterThanOrderByIdAsc(Instant updatedAt, UUID id);
}

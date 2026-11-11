package com.mymicroservice.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.product.entity.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
    boolean existsByProductIdAndIsPrimaryTrue(UUID productId);

    List<ProductImage> findAllByProductIdOrderBySortOrder(UUID productId);

    Optional<ProductImage> findByIdAndProductId(UUID id, UUID productId);

    Optional<ProductImage> findByProductIdAndIsPrimaryTrue(UUID productId);

    // 商品列表一次查出這一頁所有商品的主圖，不必每個商品各查一次
    List<ProductImage> findAllByProductIdInAndIsPrimaryTrue(Collection<UUID> productIds);

    int countByProductId(UUID productId);
}
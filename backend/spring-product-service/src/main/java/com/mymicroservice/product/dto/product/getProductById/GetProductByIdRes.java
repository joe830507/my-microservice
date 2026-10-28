package com.mymicroservice.product.dto.product.getProductById;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

public record GetProductByIdRes(
        UUID id,

        String sku,

        String name,

        String description,

        UUID categoryId,

        String brand,

        BigDecimal price,

        BigDecimal originalPrice,

        ProductStatus status,

        Instant createdAt,

        Instant updatedAt,

        // 之後修改、上下架 API 做樂觀鎖檢查時，前端需帶回此值
        Long version) {
    public static GetProductByIdRes from(Product product) {
        return new GetProductByIdRes(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getCategoryId(),
                product.getBrand(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                product.getVersion());
    }
}

package com.mymicroservice.product.dto.product.updateOneProduct;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

public record UpdateOneProductRes(
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

        // 更新後的新版本，前端下次修改時需帶回此值
        Long version) {
    // 需在 flush 之後呼叫，updatedAt 與 version 才會是更新後的值
    public static UpdateOneProductRes from(Product product) {
        return new UpdateOneProductRes(
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

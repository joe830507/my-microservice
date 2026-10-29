package com.mymicroservice.product.dto.product.getProductBySku;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

// sku 是內部料號，以 sku 查詢主要供後台使用，回傳內容與依 id 查詢相同
public record GetProductBySkuRes(
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
    public static GetProductBySkuRes from(Product product) {
        return new GetProductBySkuRes(
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

    // 快取只存一份 GetProductByIdRes，以 sku 查詢時由它轉換
    public static GetProductBySkuRes from(GetProductByIdRes res) {
        return new GetProductBySkuRes(
                res.id(),
                res.sku(),
                res.name(),
                res.description(),
                res.categoryId(),
                res.brand(),
                res.price(),
                res.originalPrice(),
                res.status(),
                res.createdAt(),
                res.updatedAt(),
                res.version());
    }
}

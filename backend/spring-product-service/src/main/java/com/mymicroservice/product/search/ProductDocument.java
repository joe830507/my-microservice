package com.mymicroservice.product.search;

import java.math.BigDecimal;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;

// 只保存搜尋與排序所需快照；狀態保留在索引，讓前台查詢自行固定過濾 ACTIVE。
public record ProductDocument(
        String id,
        String sku,
        String name,
        String brand,
        String description,
        String categoryId,
        String status,
        BigDecimal price,
        String createdAt) {

    public static ProductDocument from(Product product) {
        UUID categoryId = product.getCategoryId();
        return new ProductDocument(
                product.getId().toString(),
                product.getSku(),
                product.getName(),
                product.getBrand() == null ? "" : product.getBrand(),
                product.getDescription() == null ? "" : product.getDescription(),
                categoryId == null ? "" : categoryId.toString(),
                product.getStatus().name(),
                product.getPrice(),
                product.getCreatedAt() == null ? null : product.getCreatedAt().toString());
    }
}

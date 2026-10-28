package com.mymicroservice.product.dto.product.getProductsByIds;

import java.math.BigDecimal;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

// 只放下單需要的欄位：order-service 以 status 判斷可否購買，並將 sku、name、price 存成訂單快照
public record ProductBatchItem(
        UUID id,

        String sku,

        String name,

        BigDecimal price,

        ProductStatus status) {
    public static ProductBatchItem from(Product product) {
        return new ProductBatchItem(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getPrice(),
                product.getStatus());
    }
}

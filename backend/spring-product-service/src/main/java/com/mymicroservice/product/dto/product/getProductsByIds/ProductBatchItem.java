package com.mymicroservice.product.dto.product.getProductsByIds;

import java.math.BigDecimal;
import java.util.UUID;

import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;
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

    // 批次查詢與 getProductById 共用商品詳情快取，由它轉換
    public static ProductBatchItem from(GetProductByIdRes res) {
        return new ProductBatchItem(
                res.id(),
                res.sku(),
                res.name(),
                res.price(),
                res.status());
    }
}

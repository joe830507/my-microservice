package com.mymicroservice.product.dto.product.getProductsByIds;

import java.math.BigDecimal;
import java.util.UUID;

import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

// 只放下單需要的欄位：order-service 以 status 判斷可否購買，並將 sku、name、price 存成訂單快照
// 非上架中（ACTIVE）的商品對一般使用者只回 id 與 status，sku、name、price 為 null：
// 呼叫端只需要知道「不能買」，草稿、已下架商品的內容不該外流（見 forVisibility）
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

    // 非上架中的商品只留 id 與 status
    public static ProductBatchItem statusOnly(GetProductByIdRes res) {
        return new ProductBatchItem(res.id(), null, null, null, res.status());
    }

    // includeUnpublished 為 true（ADMIN）時所有狀態都回完整內容；否則只有 ACTIVE 回完整內容
    // 判斷在讀完快取之後，快取裡仍是完整的商品詳情（和 getProductById 一致）
    public static ProductBatchItem forVisibility(GetProductByIdRes res, boolean includeUnpublished) {
        return includeUnpublished || res.status() == ProductStatus.ACTIVE ? from(res) : statusOnly(res);
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

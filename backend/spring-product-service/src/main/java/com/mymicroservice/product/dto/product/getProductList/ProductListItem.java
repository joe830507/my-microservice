package com.mymicroservice.product.dto.product.getProductList;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

// 列表只放顯示用的欄位，description、version 等完整資料請以依 id 查詢取得
public record ProductListItem(
        UUID id,

        String sku,

        String name,

        UUID categoryId,

        String brand,

        BigDecimal price,

        BigDecimal originalPrice,

        ProductStatus status,

        Instant createdAt,

        // 主圖的公開網址；沒有主圖為 null。前台商品卡片用，不必每個商品再查一次圖片
        String primaryImageUrl) {
    public static ProductListItem from(Product product, String primaryImageUrl) {
        return new ProductListItem(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getCategoryId(),
                product.getBrand(),
                product.getPrice(),
                product.getOriginalPrice(),
                product.getStatus(),
                product.getCreatedAt(),
                primaryImageUrl);
    }
}

package com.mymicroservice.product.dto.product.changeProductStatus;

import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

// 只回傳狀態變更相關欄位，完整資料請用 GET /{id} 查詢
public record ChangeProductStatusRes(
        UUID id,

        ProductStatus status,

        Instant updatedAt,

        // 更新後的新版本，前端下次修改時需帶回此值
        Long version) {
    // 需在 flush 之後呼叫，updatedAt 與 version 才會是更新後的值
    public static ChangeProductStatusRes from(Product product) {
        return new ChangeProductStatusRes(
                product.getId(),
                product.getStatus(),
                product.getUpdatedAt(),
                product.getVersion());
    }
}

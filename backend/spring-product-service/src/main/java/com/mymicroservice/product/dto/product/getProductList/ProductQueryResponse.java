package com.mymicroservice.product.dto.product.getProductList;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.mymicroservice.product.entity.Product;

// 不直接回傳 Page，避免 JSON 結構隨 Spring Data 版本變動
public record ProductQueryResponse(
        List<ProductListItem> items,

        // 從 0 開始
        int page,

        int size,

        long totalElements,

        int totalPages) {
    // primaryImageUrls：這一頁商品的 id → 主圖網址；沒有主圖的商品不在裡面
    public static ProductQueryResponse from(Page<Product> productPage, Map<UUID, String> primaryImageUrls) {
        return new ProductQueryResponse(
                productPage.getContent().stream()
                        .map(product -> ProductListItem.from(product, primaryImageUrls.get(product.getId())))
                        .toList(),
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages());
    }
}

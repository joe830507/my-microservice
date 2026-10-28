package com.mymicroservice.product.dto.product.getProductList;

import java.util.List;

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
    public static ProductQueryResponse from(Page<Product> productPage) {
        return new ProductQueryResponse(
                productPage.getContent().stream().map(ProductListItem::from).toList(),
                productPage.getNumber(),
                productPage.getSize(),
                productPage.getTotalElements(),
                productPage.getTotalPages());
    }
}

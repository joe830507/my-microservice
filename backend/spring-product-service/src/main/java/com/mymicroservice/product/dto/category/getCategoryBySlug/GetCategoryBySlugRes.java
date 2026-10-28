package com.mymicroservice.product.dto.category.getCategoryBySlug;

import java.util.UUID;

import com.mymicroservice.product.entity.Category;

// 以 slug 查詢主要供前台（顧客端）使用，不回傳 audit、version 等後台欄位
public record GetCategoryBySlugRes(
        UUID id,

        UUID parentId,

        String name,

        String slug,

        String description,

        Integer sortOrder) {
    public static GetCategoryBySlugRes from(Category category) {
        return new GetCategoryBySlugRes(
                category.getId(),
                category.getParentId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getSortOrder());
    }
}

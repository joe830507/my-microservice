package com.mymicroservice.product.dto.category.getAllCategories;

import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.Category;

// 後台分類列表的一筆；包含停用的分類，前端依 parentId 自己組成樹
public record CategoryListItem(
        UUID id,

        // null 代表頂層分類
        UUID parentId,

        String name,

        String slug,

        String description,

        Integer sortOrder,

        Boolean active,

        Instant createdAt,

        Instant updatedAt,

        // 修改時需帶回此值做樂觀鎖檢查
        Long version) {

    public static CategoryListItem from(Category category) {
        return new CategoryListItem(
                category.getId(),
                category.getParentId(),
                category.getName(),
                category.getSlug(),
                category.getDescription(),
                category.getSortOrder(),
                category.getActive(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                category.getVersion());
    }
}

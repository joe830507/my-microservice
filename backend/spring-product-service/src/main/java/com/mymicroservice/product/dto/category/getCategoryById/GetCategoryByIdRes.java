package com.mymicroservice.product.dto.category.getCategoryById;

import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.Category;

public record GetCategoryByIdRes(
        UUID id,

        UUID parentId,

        String name,

        String slug,

        String description,

        Integer sortOrder,

        Boolean active,

        Instant createdAt,

        Instant updatedAt,

        // 之後修改 API 做樂觀鎖檢查時，前端需帶回此值
        Long version) {
    public static GetCategoryByIdRes from(Category category) {
        return new GetCategoryByIdRes(
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

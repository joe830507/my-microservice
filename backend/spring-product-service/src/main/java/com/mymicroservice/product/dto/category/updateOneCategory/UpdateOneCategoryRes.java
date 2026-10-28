package com.mymicroservice.product.dto.category.updateOneCategory;

import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.Category;

public record UpdateOneCategoryRes(
        UUID id,

        UUID parentId,

        String name,

        String slug,

        String description,

        Integer sortOrder,

        Boolean active,

        Instant createdAt,

        Instant updatedAt,

        // 更新後的新版本，前端下次修改時需帶回此值
        Long version) {
    // 需在 flush 之後呼叫，updatedAt 與 version 才會是更新後的值
    public static UpdateOneCategoryRes from(Category category) {
        return new UpdateOneCategoryRes(
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

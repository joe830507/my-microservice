package com.mymicroservice.product.dto.category.updateOneCategory;

import java.util.UUID;

import com.mymicroservice.product.entity.Category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// PUT 為整筆取代，所有可編輯欄位皆須明確給值，不補預設值
public record UpdateOneCategoryReq(
        // null 代表移到頂層
        UUID parentId,

        @NotBlank @Size(max = 100) String name,

        // 小寫英數字，以單一連字號分隔，例如 mens-shoes
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,

        // null 代表清空
        String description,

        @NotNull @PositiveOrZero Integer sortOrder,

        @NotNull Boolean active,

        // 樂觀鎖，需與目前 DB 中的版本一致才可更新
        @NotNull Long version) {

    // version 由 Hibernate 管理，不在此覆寫
    public void applyTo(Category category) {
        category.setParentId(parentId);
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(description);
        category.setSortOrder(sortOrder);
        category.setActive(active);
    }
}

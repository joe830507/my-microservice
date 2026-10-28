package com.mymicroservice.product.dto.category.addOneNewCategory;

import java.util.UUID;

import com.mymicroservice.product.entity.Category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AddOneNewCategoryReq(
        // null 代表頂層分類
        UUID parentId,

        @NotBlank @Size(max = 100) String name,

        // 小寫英數字，以單一連字號分隔，例如 mens-shoes
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$") String slug,

        String description,

        @PositiveOrZero Integer sortOrder,

        Boolean active) {

    // 未傳入的選填欄位補上與 DB 相同的預設值，避免寫入 null 違反 NOT NULL
    public AddOneNewCategoryReq {
        if (sortOrder == null) {
            sortOrder = 0;
        }
        if (active == null) {
            active = Boolean.TRUE;
        }
    }

    public Category toEntity() {
        Category category = new Category();
        category.setParentId(parentId);
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(description);
        category.setSortOrder(sortOrder);
        category.setActive(active);
        return category;
    }
}

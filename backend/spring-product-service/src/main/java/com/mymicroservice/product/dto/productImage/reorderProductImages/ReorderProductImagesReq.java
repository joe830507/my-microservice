package com.mymicroservice.product.dto.productImage.reorderProductImages;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

// 須包含該商品所有圖片的 id，list 順序即為新的排序；是否完整、是否皆屬於該商品由 service 判斷
public record ReorderProductImagesReq(
        @NotEmpty List<@NotNull UUID> imageIds) {

    // imageIds 未傳入時不檢查，交由 @NotEmpty 處理
    @AssertTrue(message = "imageIds must not contain duplicates.")
    public boolean isImageIdsDistinct() {
        return imageIds == null || new HashSet<>(imageIds).size() == imageIds.size();
    }
}

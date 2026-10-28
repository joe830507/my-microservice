package com.mymicroservice.product.dto.productImage.updateOneProductImage;

import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.ProductImage;

public record UpdateOneProductImageRes(
        UUID id,

        UUID productId,

        String url,

        String altText,

        Integer sortOrder,

        Boolean isPrimary,

        Instant createdAt,

        Instant updatedAt,

        // 更新後的新版本，前端下次修改時需帶回此值
        Long version) {

    // 需在 flush 之後呼叫，updatedAt 與 version 才會是更新後的值
    public static UpdateOneProductImageRes from(ProductImage productImage) {
        return new UpdateOneProductImageRes(
                productImage.getId(),
                productImage.getProductId(),
                productImage.getUrl(),
                productImage.getAltText(),
                productImage.getSortOrder(),
                productImage.getIsPrimary(),
                productImage.getCreatedAt(),
                productImage.getUpdatedAt(),
                productImage.getVersion());
    }
}

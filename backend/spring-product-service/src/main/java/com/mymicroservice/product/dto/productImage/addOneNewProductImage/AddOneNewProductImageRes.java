package com.mymicroservice.product.dto.productImage.addOneNewProductImage;

import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.product.entity.ProductImage;

public record AddOneNewProductImageRes(
        UUID id,

        UUID productId,

        String url,

        String altText,

        Integer sortOrder,

        Boolean isPrimary,

        Instant createdAt,

        // 之後修改圖片資訊時，前端需帶回此值
        Long version) {

    // 需在 flush 之後呼叫，createdAt 與 version 才會有值
    public static AddOneNewProductImageRes from(ProductImage productImage) {
        return new AddOneNewProductImageRes(
                productImage.getId(),
                productImage.getProductId(),
                productImage.getUrl(),
                productImage.getAltText(),
                productImage.getSortOrder(),
                productImage.getIsPrimary(),
                productImage.getCreatedAt(),
                productImage.getVersion());
    }
}

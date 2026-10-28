package com.mymicroservice.product.dto.productImage.getProductImages;

import java.util.UUID;

import com.mymicroservice.product.entity.ProductImage;

public record ProductImageItem(
        UUID id,

        String url,

        String altText,

        Integer sortOrder,

        Boolean isPrimary,

        // 修改圖片資訊時，前端需帶回此值
        Long version) {

    public static ProductImageItem from(ProductImage productImage) {
        return new ProductImageItem(
                productImage.getId(),
                productImage.getUrl(),
                productImage.getAltText(),
                productImage.getSortOrder(),
                productImage.getIsPrimary(),
                productImage.getVersion());
    }
}

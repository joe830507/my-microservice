package com.mymicroservice.product.dto.productImage.getProductImages;

import java.util.function.UnaryOperator;
import java.util.UUID;

import com.mymicroservice.product.entity.ProductImage;

public record ProductImageItem(
        UUID id,

        // 公開網址，由 object key 組成（換 CDN 只要改設定）
        String url,

        String altText,

        Integer sortOrder,

        Boolean isPrimary,

        // 修改圖片資訊時，前端需帶回此值
        Long version) {

    public static ProductImageItem from(ProductImage productImage, UnaryOperator<String> urlOf) {
        return new ProductImageItem(
                productImage.getId(),
                urlOf.apply(productImage.getObjectKey()),
                productImage.getAltText(),
                productImage.getSortOrder(),
                productImage.getIsPrimary(),
                productImage.getVersion());
    }
}

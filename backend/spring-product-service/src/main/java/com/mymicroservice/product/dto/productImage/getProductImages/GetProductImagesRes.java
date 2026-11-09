package com.mymicroservice.product.dto.productImage.getProductImages;

import java.util.function.UnaryOperator;
import java.util.List;
import java.util.UUID;

import com.mymicroservice.product.entity.ProductImage;

// 查詢列表、設定主圖、調整排序共用，images 順序即為顯示順序
public record GetProductImagesRes(
        UUID productId,

        List<ProductImageItem> images) {

    // 傳入的 productImages 須已依 sortOrder 排序
    public static GetProductImagesRes from(UUID productId, List<ProductImage> productImages,
            UnaryOperator<String> urlOf) {
        return new GetProductImagesRes(productId, productImages.stream().map(image -> ProductImageItem.from(image, urlOf)).toList());
    }
}

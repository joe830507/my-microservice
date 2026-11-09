package com.mymicroservice.product.dto.productImage.addOneNewProductImage;

import java.util.UUID;


import com.mymicroservice.product.entity.ProductImage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 不接受 sortOrder 與 isPrimary：排序固定排在最後，主圖由 service 判斷（第一張自動設為主圖）或透過設定主圖 API 變更
public record AddOneNewProductImageReq(
        // 申請上傳網址時拿到的 objectKey（tmp/products/{productId}/...），檔案已經用上傳網址 PUT 完成
        @NotBlank @Size(max = 500) String objectKey,

        // null 代表不提供替代文字
        @Size(max = 255) String altText) {

    // sortOrder 與 isPrimary 由 service 計算後再設定；storedObjectKey 是搬到 products/ 之後的 key
    public ProductImage toEntity(UUID productId, String storedObjectKey) {
        ProductImage productImage = new ProductImage();
        productImage.setProductId(productId);
        productImage.setObjectKey(storedObjectKey);
        productImage.setAltText(altText);
        return productImage;
    }
}

package com.mymicroservice.product.dto.productImage.addOneNewProductImage;

import java.util.UUID;

import org.hibernate.validator.constraints.URL;

import com.mymicroservice.product.entity.ProductImage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 不接受 sortOrder 與 isPrimary：排序固定排在最後，主圖由 service 判斷（第一張自動設為主圖）或透過設定主圖 API 變更
public record AddOneNewProductImageReq(
        // 前端上傳至外部儲存後取得的網址
        @NotBlank @Size(max = 500) @URL String url,

        // null 代表不提供替代文字
        @Size(max = 255) String altText) {

    // sortOrder 與 isPrimary 由 service 計算後再設定
    public ProductImage toEntity(UUID productId) {
        ProductImage productImage = new ProductImage();
        productImage.setProductId(productId);
        productImage.setUrl(url);
        productImage.setAltText(altText);
        return productImage;
    }
}

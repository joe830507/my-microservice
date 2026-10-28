package com.mymicroservice.product.dto.productImage.updateOneProductImage;

import com.mymicroservice.product.entity.ProductImage;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// PUT 為整筆取代可編輯欄位；url 不可修改（換圖請刪除後重新新增），主圖與排序須透過專用 API 變更
public record UpdateOneProductImageReq(
        // null 代表清空
        @Size(max = 255) String altText,

        // 樂觀鎖，需與目前 DB 中的版本一致才可更新
        @NotNull Long version) {

    // version 由 Hibernate 管理，不在此覆寫
    public void applyTo(ProductImage productImage) {
        productImage.setAltText(altText);
    }
}

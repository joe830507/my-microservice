package com.mymicroservice.product.dto.product.updateOneProduct;

import java.math.BigDecimal;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// PUT 為整筆取代，所有可編輯欄位皆須明確給值，不補預設值；status 須透過 publish / unpublish / archive 變更
public record UpdateOneProductReq(
        @NotBlank @Size(max = 64) String sku,

        @NotBlank @Size(max = 255) String name,

        // null 代表清空
        String description,

        // null 代表取消分類，上架中的商品是否允許由 service 判斷
        UUID categoryId,

        // null 代表清空
        @Size(max = 100) String brand,

        // 對應 NUMERIC(12,2)：整數最多 10 位、小數最多 2 位
        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal price,

        // null 代表沒有原價（不顯示折扣）
        @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal originalPrice,

        // 樂觀鎖，需與目前 DB 中的版本一致才可更新
        @NotNull Long version) {

    // 任一邊未傳入時不檢查，交由各自的欄位驗證處理
    @AssertTrue(message = "originalPrice must be greater than or equal to price.")
    public boolean isOriginalPriceGreaterThanOrEqualToPrice() {
        return originalPrice == null || price == null || originalPrice.compareTo(price) >= 0;
    }

    // version 由 Hibernate 管理，status 由狀態流轉 API 管理，皆不在此覆寫
    public void applyTo(Product product) {
        product.setSku(sku);
        product.setName(name);
        product.setDescription(description);
        product.setCategoryId(categoryId);
        product.setBrand(brand);
        product.setPrice(price);
        product.setOriginalPrice(originalPrice);
    }
}

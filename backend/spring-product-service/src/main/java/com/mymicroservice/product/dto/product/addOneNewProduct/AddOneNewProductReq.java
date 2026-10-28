package com.mymicroservice.product.dto.product.addOneNewProduct;

import java.math.BigDecimal;
import java.util.UUID;

import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 不接受 status，新增時固定為 DRAFT
public record AddOneNewProductReq(
                @NotBlank @Size(max = 64) String sku,

                @NotBlank @Size(max = 255) String name,

                String description,

                // 草稿可先不指定分類，上架前才檢查
                UUID categoryId,

                @Size(max = 100) String brand,

                // 對應 NUMERIC(12,2)：整數最多 10 位、小數最多 2 位
                @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal price,

                // null 代表沒有原價（不顯示折扣）
                @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal originalPrice) {

        @AssertTrue(message = "originalPrice must be greater than or equal to price.")
        public boolean isOriginalPriceGeOrEqualsPrice() {
                return originalPrice == null || (originalPrice != null && originalPrice.compareTo(price) >= 0);
        }

        public Product toEntity() {
                Product product = new Product();
                product.setBrand(brand);
                product.setCategoryId(categoryId);
                product.setDescription(description);
                product.setName(name);
                product.setOriginalPrice(originalPrice);
                product.setPrice(price);
                product.setSku(sku);
                product.setStatus(ProductStatus.DRAFT);
                return product;
        }
}

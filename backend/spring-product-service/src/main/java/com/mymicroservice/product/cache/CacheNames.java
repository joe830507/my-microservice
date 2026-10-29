package com.mymicroservice.product.cache;

public final class CacheNames {
    private CacheNames() {
    }

    public static final String PRODUCT_DETAIL = "productDetail";
    // 只存 sku 對應的商品 id，商品詳情統一由 PRODUCT_DETAIL 保存，失效時只需刪一份
    public static final String PRODUCT_SKU_TO_ID = "productSkuToId";
}

package com.mymicroservice.product.cache;

public final class CacheNames {
    private CacheNames() {
    }

    public static final String PRODUCT_DETAIL = "productDetail";
    // 只存 sku 對應的商品 id，商品詳情統一由 PRODUCT_DETAIL 保存，失效時只需刪一份
    public static final String PRODUCT_SKU_TO_ID = "productSkuToId";

    // RedisCacheManager 的 key 前綴，與直接用 RedisTemplate 讀取時共用，避免兩邊格式不一致
    public static String prefix(String cacheName) {
        return "product:" + cacheName + ":v1:";
    }

    public static String redisKey(String cacheName, Object key) {
        return prefix(cacheName) + key;
    }
}

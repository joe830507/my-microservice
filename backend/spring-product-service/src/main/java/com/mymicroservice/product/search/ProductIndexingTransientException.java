package com.mymicroservice.product.search;

// 僅包裝 ES 連線與暫時性 HTTP 錯誤，專用 listener 才會對這類錯誤無限退避重試。
public class ProductIndexingTransientException extends RuntimeException {
    public ProductIndexingTransientException(Throwable cause) {
        super("Elasticsearch 暫時無法索引商品事件", cause);
    }
}

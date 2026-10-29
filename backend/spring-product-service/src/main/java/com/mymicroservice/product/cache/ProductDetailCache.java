package com.mymicroservice.product.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 讀取用 @Cacheable，查不到時回傳 Optional.empty()，會以短 TTL 快取 null 防止穿透
// @Cacheable 方法須由其他 bean 呼叫，類別內部互相呼叫不會經過代理，快取不會生效
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDetailCache {
    private final ProductRepository productRepository;
    private final CacheManager cacheManager;

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.PRODUCT_DETAIL, key = "#id")
    public Optional<GetProductByIdRes> findById(UUID id) {
        return productRepository.findById(id).map(GetProductByIdRes::from);
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheNames.PRODUCT_SKU_TO_ID, key = "#sku")
    public Optional<UUID> findIdBySku(String sku) {
        return productRepository.findBySku(sku).map(Product::getId);
    }

    public void evictAfterCommit(UUID id) {
        runAfterCommit(() -> evict(CacheNames.PRODUCT_DETAIL, id));
    }

    public void evictSkuMappingAfterCommit(String sku) {
        runAfterCommit(() -> evict(CacheNames.PRODUCT_SKU_TO_ID, sku));
    }

    // 查詢時發現對照已過期使用，此時沒有交易，直接刪除
    public void evictSkuMapping(String sku) {
        evict(CacheNames.PRODUCT_SKU_TO_ID, sku);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // 直接操作 Cache 不會經過 CacheErrorHandler，需自行 catch
    // afterCommit 中丟出例外，會讓已經 commit 的請求回 500
    private void evict(String cacheName, Object key) {
        try {
            Objects.requireNonNull(cacheManager.getCache(cacheName)).evict(key);
        } catch (Exception e) {
            log.warn("Failed to evict cache {} key {}", cacheName, key, e);
        }
    }
}

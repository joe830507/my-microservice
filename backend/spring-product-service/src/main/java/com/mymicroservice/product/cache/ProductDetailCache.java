package com.mymicroservice.product.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.repository.ProductRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

// 讀取用 @Cacheable，查不到時回傳 Optional.empty()，會以短 TTL 快取 null 防止穿透
// @Cacheable 方法須由其他 bean 呼叫，類別內部互相呼叫不會經過代理，快取不會生效
// 不使用 sync = true：sync 會走 Cache#get(key, Callable)，RedisCache 在載入前就計算 TTL，
// TTL function 拿到的 value 永遠是 null，所有資料都會被當成「查不到」只存 90 秒
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDetailCache {
    private final ProductRepository productRepository;
    private final CacheManager cacheManager;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

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

    // 不加 @Cacheable：批次查詢要逐筆讀寫 PRODUCT_DETAIL，與 findById 共用同一份快取
    // 不加 @Transactional：全部命中時不需要 DB 連線，findAllById 本身就有唯讀交易
    // 不存在的 id 不會出現在結果中，結果順序不保證與傳入的 ids 相同
    public List<GetProductByIdRes> findAllByIds(List<UUID> ids) {
        List<UUID> distinctIds = ids.stream().distinct().toList();
        Map<UUID, GetProductByIdRes> found = new LinkedHashMap<>();

        // 1. MGET 讀快取
        List<UUID> missedIds = readFromCache(distinctIds, found);

        // 2. 未命中的一次查 DB，並回填
        if (!missedIds.isEmpty()) {
            Cache cache = Objects.requireNonNull(cacheManager.getCache(CacheNames.PRODUCT_DETAIL));
            Map<UUID, GetProductByIdRes> loaded = productRepository.findAllById(missedIds).stream()
                    .map(GetProductByIdRes::from)
                    .collect(Collectors.toMap(GetProductByIdRes::id, r -> r));
            for (UUID id : missedIds) {
                GetProductByIdRes res = loaded.get(id);
                safePut(cache, id, res); // res 為 null 時存 null，短 TTL 防穿透
                if (res != null) {
                    found.put(id, res);
                }
            }
        }
        return List.copyOf(found.values());
    }

    // 命中的放進 found，回傳未命中的 id
    // Redis 出錯時整批視為未命中；先收集完才寫入 found，避免讀到一半出錯時遺漏部分 id
    private List<UUID> readFromCache(List<UUID> ids, Map<UUID, GetProductByIdRes> found) {
        if (ids.isEmpty()) {
            return List.of();
        }
        try {
            List<String> keys = ids.stream()
                    .map(id -> CacheNames.redisKey(CacheNames.PRODUCT_DETAIL, id))
                    .toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null) {
                return ids;
            }
            Map<UUID, GetProductByIdRes> hits = new LinkedHashMap<>();
            List<UUID> missedIds = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                GetProductByIdRes res = parse(values.get(i));
                if (res != null) {
                    hits.put(ids.get(i), res);
                } else {
                    missedIds.add(ids.get(i));
                }
            }
            found.putAll(hits);
            return missedIds;
        } catch (Exception e) {
            log.warn("Failed to multiGet cache {}, fallback to DB", CacheNames.PRODUCT_DETAIL, e);
            return ids;
        }
    }

    // 回填失敗不影響查詢結果，只記 log
    private void safePut(Cache cache, UUID id, GetProductByIdRes res) {
        try {
            cache.put(id, res);
        } catch (Exception e) {
            log.warn("Failed to put cache id {}", id, e);
        }
    }

    // 解析失敗（包含快取的 null，它不是 JSON）一律當未命中
    private GetProductByIdRes parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(value, GetProductByIdRes.class);
        } catch (Exception e) {
            return null;
        }
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

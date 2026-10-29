package com.mymicroservice.product.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mymicroservice.product.dto.category.getCategoriesTree.GetCategoriesTreeRes;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class CategoryTreeCache {
    public static final String KEY = "product:category:tree:v1";
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    public Optional<GetCategoriesTreeRes> get() {
        GetCategoriesTreeRes getCategoriesTreeRes = null;
        try {
            String value = redisTemplate.opsForValue().get(KEY);
            if (value == null) {
                return Optional.empty();
            }
            getCategoriesTreeRes = jsonMapper.readValue(value,
                    GetCategoriesTreeRes.class);
        } catch (DataAccessException e) {
            log.warn("Failed to access redis key {}", KEY, e);
        } catch (Exception e) {
            log.warn("Failed to read redis key {}", KEY, e);
        }
        return Optional.ofNullable(getCategoriesTreeRes);
    }

    public void put(GetCategoriesTreeRes tree) {
        try {
            long randomPlusTime = ThreadLocalRandom.current().nextLong(0, 300);
            redisTemplate.opsForValue().set(KEY, jsonMapper.writeValueAsString(tree),
                    Duration.ofHours(1).plusSeconds(randomPlusTime));
        } catch (Exception e) {
            log.warn("Failed to put redis key {}", KEY, e);
        }
    }

    void evict() {
        try {
            redisTemplate.delete(KEY);
        } catch (Exception e) {
            log.warn("Failed to delete redis key {}", KEY, e);
        }
    }

    // 操作A跟B，A已經開始交易，尚未commit，此時evict，操作B讀取，快取沒有命中，讀資料庫(仍是舊值)，資料再放入快取，舊資料又回去了，操作A此時才commit。
    // 避免此情況，一律都是在commit之後再刪除快取，這樣永遠都是命中新快取。
    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronization transactionSynchronization = new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict();
                }
            };
            TransactionSynchronizationManager.registerSynchronization(transactionSynchronization);
        } else {
            evict();
        }
    }
}

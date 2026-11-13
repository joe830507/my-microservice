package com.mymicroservice.product.cache;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.mymicroservice.product.dto.category.getCategoriesTree.GetCategoriesTreeRes;
import com.mymicroservice.product.entity.Category;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.databind.json.JsonMapper;

@Component
@Slf4j
@RequiredArgsConstructor
public class CategoryTreeCache {
    public static final String KEY = "product:category:tree:v1";
    public static final String LINKS_KEY = "product:category:links:v1";
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
            redisTemplate.opsForValue().set(KEY, jsonMapper.writeValueAsString(tree),
                    treeTtl());
        } catch (Exception e) {
            log.warn("Failed to put redis key {}", KEY, e);
        }
    }

    // 連同停用分類一起快取父子關係，保留與公開分類樹不同的查詢語意。
    public Set<UUID> descendantIds(UUID root, Supplier<List<Category>> loader) {
        CategoryLink[] links = null;
        try {
            String cached = redisTemplate.opsForValue().get(LINKS_KEY);
            if (cached != null) links = jsonMapper.readValue(cached, CategoryLink[].class);
        } catch (Exception failure) {
            log.warn("Failed to read category links cache", failure);
        }
        if (links == null) {
            links = loader.get().stream().map(c -> new CategoryLink(c.getId(), c.getParentId()))
                    .toArray(CategoryLink[]::new);
            try {
                redisTemplate.opsForValue().set(LINKS_KEY, jsonMapper.writeValueAsString(links), treeTtl());
            } catch (Exception failure) {
                log.warn("Failed to write category links cache", failure);
            }
        }
        var children = Arrays.stream(links).filter(link -> link.parentId() != null)
                .collect(Collectors.groupingBy(CategoryLink::parentId));
        Set<UUID> result = new HashSet<>();
        var pending = new ArrayDeque<UUID>();
        pending.add(root);
        while (!pending.isEmpty()) {
            UUID id = pending.pop();
            if (result.add(id)) children.getOrDefault(id, List.of()).forEach(link -> pending.add(link.id()));
        }
        return result;
    }

    private static Duration treeTtl() {
        return Duration.ofHours(1).plusSeconds(ThreadLocalRandom.current().nextLong(0, 300));
    }

    public record CategoryLink(UUID id, UUID parentId) {}

    void evict() {
        try {
            redisTemplate.delete(List.of(KEY, LINKS_KEY));
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

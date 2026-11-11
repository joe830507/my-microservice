package com.mymicroservice.product.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.dto.product.addOneNewProduct.AddOneNewProductReq;
import com.mymicroservice.product.dto.product.addOneNewProduct.AddOneNewProductRes;
import com.mymicroservice.product.dto.product.changeProductStatus.ChangeProductStatusReq;
import com.mymicroservice.product.dto.product.getProductsByIds.ProductBatchItem;
import com.mymicroservice.product.dto.product.updateOneProduct.UpdateOneProductReq;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;
import com.mymicroservice.product.exception.ProductErrorCode;
import com.mymicroservice.product.service.ProductService;

// 透過 ProductService 驗證商品詳情與 sku 對照快取的讀取與失效，DB 與 Redis 皆為 Testcontainers 啟動的真實容器
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProductDetailCacheIntegrationTests {
    @Autowired
    private ProductService productService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        // 每個測試都從空的 DB 與空的快取開始
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
        Set<String> keys = redisTemplate.keys("product:*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ---------- getProductById ----------

    @Test
    void getProductById_cacheMiss_loadsFromDbAndStoresInRedis() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");

        assertThat(productService.getProductById(product.id(), true).name()).isEqualTo("球鞋");

        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isTrue();
        // TTL 為 30 分鐘加上 0~5 分鐘的隨機值
        assertThat(redisTemplate.getExpire(detailKey(product.id()))).isBetween(1790L, 2100L);
    }

    @Test
    void getProductById_cacheHit_returnsCachedDetailWithoutQueryingDb() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        productService.getProductById(product.id(), true);

        // 繞過 Service 直接改 DB，快取不會失效；若仍回傳舊資料，代表這次沒有查 DB
        jdbcTemplate.update("UPDATE product_service.product SET name = ? WHERE id = ?", "改名", product.id());

        assertThat(productService.getProductById(product.id(), true).name()).isEqualTo("球鞋");
    }

    @Test
    void getProductById_notFound_cachesNullWithShortTtl() {
        UUID missingId = UUID.randomUUID();

        assertNotFound(() -> productService.getProductById(missingId, true));

        assertThat(redisTemplate.hasKey(detailKey(missingId))).isTrue();
        assertThat(redisTemplate.getExpire(detailKey(missingId))).isBetween(1L, 90L);
    }

    @Test
    void getProductById_notFoundCached_doesNotQueryDbAgain() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        UUID id = product.id();
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
        assertNotFound(() -> productService.getProductById(id, true));

        // 繞過 Service 把資料放回 DB，快取的 null 還沒過期，仍應回 404
        jdbcTemplate.update("""
                INSERT INTO product_service.product (id, sku, name, price, created_by, updated_by)
                VALUES (?, 'SKU-001', '球鞋', 100, ?, ?)
                """, id, UUID.randomUUID(), UUID.randomUUID());

        assertNotFound(() -> productService.getProductById(id, true));
    }

    // ---------- getProductBySku ----------

    @Test
    void getProductBySku_cacheMiss_storesSkuMappingAndDetail() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");

        assertThat(productService.getProductBySku("SKU-001", true).id()).isEqualTo(product.id());

        // sku 對照只存 id，詳情與 getProductById 共用同一份
        assertThat(redisTemplate.opsForValue().get(skuKey("SKU-001"))).contains(product.id().toString());
        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isTrue();
    }

    @Test
    void getProductBySku_notFound_cachesNull() {
        assertNotFound(() -> productService.getProductBySku("NOPE", true));

        assertThat(redisTemplate.hasKey(skuKey("NOPE"))).isTrue();
        assertThat(redisTemplate.getExpire(skuKey("NOPE"))).isBetween(1L, 90L);
    }

    @Test
    void addOneNewProduct_evictsCachedNotFoundSku() {
        // 建立前先查過，查不到的結果被快取
        assertNotFound(() -> productService.getProductBySku("SKU-001", true));

        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");

        assertThat(redisTemplate.hasKey(skuKey("SKU-001"))).isFalse();
        assertThat(productService.getProductBySku("SKU-001", true).id()).isEqualTo(product.id());
    }

    @Test
    void getProductBySku_staleMapping_evictsAndReloadsFromDb() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        productService.getProductBySku("SKU-001", true);

        // 模擬對照過期：DB 的 sku 已改，詳情快取也已失效，但 SKU-001 的對照還在
        jdbcTemplate.update("UPDATE product_service.product SET sku = 'SKU-002' WHERE id = ?", product.id());
        redisTemplate.delete(detailKey(product.id()));

        assertNotFound(() -> productService.getProductBySku("SKU-001", true));
        // 過期的對照被刪除後重查 DB，查不到的結果改存成 null
        assertThat(redisTemplate.opsForValue().get(skuKey("SKU-001"))).doesNotContain(product.id().toString());
        assertThat(productService.getProductBySku("SKU-002", true).id()).isEqualTo(product.id());
    }

    // ---------- getProductsByIds ----------

    @Test
    void getProductsByIds_cacheMiss_loadsFromDbAndStoresEachDetail() {
        AddOneNewProductRes a = addProduct("SKU-001", "球鞋");
        AddOneNewProductRes b = addProduct("SKU-002", "拖鞋");

        assertThat(productService.getProductsByIds(List.of(a.id(), b.id()), true).products())
                .extracting(ProductBatchItem::id)
                .containsExactlyInAnyOrder(a.id(), b.id());

        // 逐筆存進 PRODUCT_DETAIL，TTL 與 getProductById 相同
        assertThat(redisTemplate.getExpire(detailKey(a.id()))).isBetween(1790L, 2100L);
        assertThat(redisTemplate.getExpire(detailKey(b.id()))).isBetween(1790L, 2100L);
    }

    @Test
    void getProductsByIds_sharesCacheWithGetProductById() {
        AddOneNewProductRes a = addProduct("SKU-001", "球鞋");
        AddOneNewProductRes b = addProduct("SKU-002", "拖鞋");
        // 只有 a 先被單筆查詢暖過
        productService.getProductById(a.id(), true);

        jdbcTemplate.update("UPDATE product_service.product SET name = '改名'");

        // a 命中快取回舊名稱；b 未命中查 DB 拿到新名稱
        assertThat(productService.getProductsByIds(List.of(a.id(), b.id()), true).products())
                .extracting(ProductBatchItem::id, ProductBatchItem::name)
                .containsExactlyInAnyOrder(tuple(a.id(), "球鞋"), tuple(b.id(), "改名"));
        // 批次回填的 b，單筆查詢也能命中
        assertThat(productService.getProductById(b.id(), true).name()).isEqualTo("改名");
    }

    @Test
    void getProductsByIds_missingIdsAreOmittedAndCachedAsNull() {
        AddOneNewProductRes a = addProduct("SKU-001", "球鞋");
        UUID missingId = UUID.randomUUID();

        assertThat(productService.getProductsByIds(List.of(a.id(), missingId, a.id()), true).products())
                .extracting(ProductBatchItem::id)
                .containsExactly(a.id());

        assertThat(redisTemplate.getExpire(detailKey(missingId))).isBetween(1L, 90L);
        // 批次存的 null，單筆查詢也會判為不存在
        assertNotFound(() -> productService.getProductById(missingId, true));
    }

    @Test
    void getProductsByIds_afterUpdate_returnsFreshData() {
        AddOneNewProductRes a = addProduct("SKU-001", "球鞋");
        productService.getProductsByIds(List.of(a.id()), true);

        productService.updateOneProduct(a.id(), updateReq(a, "SKU-001", "跑鞋"));

        assertThat(productService.getProductsByIds(List.of(a.id()), true).products())
                .extracting(ProductBatchItem::name)
                .containsExactly("跑鞋");
    }

    // ---------- updateOneProduct ----------

    @Test
    void updateOneProduct_success_evictsDetailAndSkuMapping() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        warmUpCache(product);

        productService.updateOneProduct(product.id(), updateReq(product, "SKU-001", "跑鞋"));

        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isFalse();
        assertThat(redisTemplate.hasKey(skuKey("SKU-001"))).isFalse();
        assertThat(productService.getProductById(product.id(), true).name()).isEqualTo("跑鞋");
    }

    @Test
    void updateOneProduct_skuChanged_oldSkuNotFoundAndNewSkuFound() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        warmUpCache(product);
        // 改之前先查過新 sku，查不到的結果被快取
        assertNotFound(() -> productService.getProductBySku("SKU-002", true));

        productService.updateOneProduct(product.id(), updateReq(product, "SKU-002", "球鞋"));

        assertThat(redisTemplate.hasKey(skuKey("SKU-002"))).isFalse();
        assertNotFound(() -> productService.getProductBySku("SKU-001", true));
        assertThat(productService.getProductBySku("SKU-002", true).id()).isEqualTo(product.id());
    }

    @Test
    void updateOneProduct_versionConflict_keepsCache() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        warmUpCache(product);

        UpdateOneProductReq staleReq = new UpdateOneProductReq(
                "SKU-001", "跑鞋", null, null, null, BigDecimal.valueOf(100), null, product.version() + 1);

        assertThatThrownBy(() -> productService.updateOneProduct(product.id(), staleReq))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VERSION_CONFLICT));
        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isTrue();
        assertThat(redisTemplate.hasKey(skuKey("SKU-001"))).isTrue();
    }

    @Test
    void updateOneProduct_insideOuterTransaction_evictsOnlyAfterCommit() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        warmUpCache(product);

        // updateOneProduct 會加入外層交易，要等外層 commit 才刪快取
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            productService.updateOneProduct(product.id(), updateReq(product, "SKU-001", "跑鞋"));
            assertThat(redisTemplate.hasKey(detailKey(product.id()))).isTrue();
        });

        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isFalse();
    }

    @Test
    void updateOneProduct_outerTransactionRolledBack_keepsCache() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        warmUpCache(product);

        // updateOneProduct 本身沒有丟例外，但外層交易 rollback，DB 沒有變，快取也不該刪
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            productService.updateOneProduct(product.id(), updateReq(product, "SKU-001", "跑鞋"));
            status.setRollbackOnly();
        });

        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isTrue();
        assertThat(productService.getProductById(product.id(), true).name()).isEqualTo("球鞋");
    }

    // ---------- 狀態變更 ----------

    @Test
    void archiveProduct_evictsDetail() {
        AddOneNewProductRes product = addProduct("SKU-001", "球鞋");
        warmUpCache(product);

        productService.archiveProduct(product.id(), new ChangeProductStatusReq(product.version()));

        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isFalse();
        assertThat(productService.getProductById(product.id(), true).status()).isEqualTo(ProductStatus.ARCHIVED);
    }

    // ---------- helpers ----------

    private AddOneNewProductRes addProduct(String sku, String name) {
        return productService.addOneNewProduct(
                new AddOneNewProductReq(sku, name, null, null, null, BigDecimal.valueOf(100), null));
    }

    private UpdateOneProductReq updateReq(AddOneNewProductRes product, String sku, String name) {
        return new UpdateOneProductReq(
                sku, name, null, null, null, BigDecimal.valueOf(100), null, product.version());
    }

    private void warmUpCache(AddOneNewProductRes product) {
        productService.getProductBySku(product.sku(), true);
        assertThat(redisTemplate.hasKey(detailKey(product.id()))).isTrue();
        assertThat(redisTemplate.hasKey(skuKey(product.sku()))).isTrue();
    }

    private void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    private static String detailKey(UUID id) {
        return CacheNames.redisKey(CacheNames.PRODUCT_DETAIL, id);
    }

    private static String skuKey(String sku) {
        return CacheNames.redisKey(CacheNames.PRODUCT_SKU_TO_ID, sku);
    }
}

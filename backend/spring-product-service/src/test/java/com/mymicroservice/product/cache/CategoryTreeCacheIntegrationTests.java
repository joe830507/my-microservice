package com.mymicroservice.product.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryReq;
import com.mymicroservice.product.dto.category.addOneNewCategory.AddOneNewCategoryRes;
import com.mymicroservice.product.dto.category.getCategoriesTree.CategoryTreeNode;
import com.mymicroservice.product.dto.category.getCategoriesTree.GetCategoriesTreeRes;
import com.mymicroservice.product.dto.category.updateOneCategory.UpdateOneCategoryReq;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;
import com.mymicroservice.product.service.CategoryService;

// 透過 CategoryService 驗證分類樹快取的讀取與失效，DB 與 Redis 皆為 Testcontainers 啟動的真實容器
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CategoryTreeCacheIntegrationTests {
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        // 每個測試都從空的 DB 與空的快取開始
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
        redisTemplate.delete(CategoryTreeCache.KEY);
    }

    @Test
    void getCategoriesTree_cacheMiss_loadsFromDbAndStoresInRedis() {
        addCategory("鞋子", "shoes");

        GetCategoriesTreeRes tree = categoryService.getCategoriesTree();

        assertThat(tree.roots()).extracting(CategoryTreeNode::slug).containsExactly("shoes");
        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isTrue();
        // TTL 為 1 小時加上 0~5 分鐘的隨機值
        assertThat(redisTemplate.getExpire(CategoryTreeCache.KEY)).isBetween(3500L, 3900L);
    }

    @Test
    void getCategoriesTree_cacheHit_returnsCachedTreeWithoutQueryingDb() {
        addCategory("鞋子", "shoes");
        categoryService.getCategoriesTree();

        // 繞過 Service 直接刪 DB，快取不會失效；若仍回傳舊資料，代表這次沒有查 DB
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");

        assertThat(categoryService.getCategoriesTree().roots())
                .extracting(CategoryTreeNode::slug)
                .containsExactly("shoes");
    }

    @Test
    void addOneNewCategory_evictsCache() {
        addCategory("鞋子", "shoes");
        warmUpCache();

        addCategory("包包", "bags");

        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isFalse();
        assertThat(categoryService.getCategoriesTree().roots())
                .extracting(CategoryTreeNode::slug)
                .containsExactlyInAnyOrder("shoes", "bags");
    }

    @Test
    void updateOneCategory_success_evictsCache() {
        AddOneNewCategoryRes category = addCategory("鞋子", "shoes");
        warmUpCache();

        categoryService.updateOneCategory(category.id(), renameReq(category, "運動鞋"));

        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isFalse();
        assertThat(categoryService.getCategoriesTree().roots())
                .extracting(CategoryTreeNode::name)
                .containsExactly("運動鞋");
    }

    @Test
    void updateOneCategory_versionConflict_keepsCache() {
        AddOneNewCategoryRes category = addCategory("鞋子", "shoes");
        warmUpCache();

        UpdateOneCategoryReq staleReq = new UpdateOneCategoryReq(
                null, "運動鞋", "shoes", null, 0, true, category.version() + 1);

        assertThatThrownBy(() -> categoryService.updateOneCategory(category.id(), staleReq))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.VERSION_CONFLICT));
        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isTrue();
    }

    @Test
    void deleteOneCategoryById_evictsCache() {
        AddOneNewCategoryRes category = addCategory("鞋子", "shoes");
        warmUpCache();

        categoryService.deleteOneCategoryById(category.id());

        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isFalse();
        assertThat(categoryService.getCategoriesTree().roots()).isEmpty();
    }

    @Test
    void updateOneCategory_insideOuterTransaction_evictsOnlyAfterCommit() {
        AddOneNewCategoryRes category = addCategory("鞋子", "shoes");
        warmUpCache();

        // updateOneCategory 會加入外層交易，要等外層 commit 才刪快取
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            categoryService.updateOneCategory(category.id(), renameReq(category, "運動鞋"));
            assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isTrue();
        });

        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isFalse();
    }

    @Test
    void updateOneCategory_outerTransactionRolledBack_keepsCache() {
        AddOneNewCategoryRes category = addCategory("鞋子", "shoes");
        warmUpCache();

        // updateOneCategory 本身沒有丟例外，但外層交易 rollback，DB 沒有變，快取也不該刪
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            categoryService.updateOneCategory(category.id(), renameReq(category, "運動鞋"));
            status.setRollbackOnly();
        });

        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isTrue();
        assertThat(categoryService.getCategoriesTree().roots())
                .extracting(CategoryTreeNode::name)
                .containsExactly("鞋子");
    }

    private AddOneNewCategoryRes addCategory(String name, String slug) {
        return categoryService.addOneNewCategory(new AddOneNewCategoryReq(null, name, slug, null, null, null));
    }

    private void warmUpCache() {
        categoryService.getCategoriesTree();
        assertThat(redisTemplate.hasKey(CategoryTreeCache.KEY)).isTrue();
    }

    private UpdateOneCategoryReq renameReq(AddOneNewCategoryRes category, String newName) {
        return new UpdateOneCategoryReq(
                null, newName, category.slug(), null, 0, true, category.version());
    }
}

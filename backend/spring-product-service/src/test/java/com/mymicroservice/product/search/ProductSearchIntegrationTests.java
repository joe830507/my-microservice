package com.mymicroservice.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.entity.Category;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.product.repository.CategoryRepository;
import com.mymicroservice.product.repository.ProductRepository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 前台關鍵字搜尋（D5）：真的 Elasticsearch（含 smartcn）、PostgreSQL；索引用 D4 的重建流程建立
@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "search.rebuild.on-startup=false",
        "search.elasticsearch.readiness-cache-ttl=0s",
        "app.events.outbox-poll-interval=3600000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductSearchIntegrationTests {
    @Container
    static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
            new ImageFromDockerfile("my-microservice/elasticsearch-d5-test:9.4.5-smartcn", false)
                    .withDockerfile(Path.of("../../infra/elasticsearch/Dockerfile")))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("node.store.allow_mmap", "false")
            .waitingFor(Wait.forLogMessage(".*started.*", 1));

    @DynamicPropertySource
    static void elasticsearchEndpoint(DynamicPropertyRegistry properties) {
        properties.add("search.elasticsearch.endpoint", () -> "http://localhost:" + ELASTICSEARCH.getMappedPort(9200));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductIndexRebuildService rebuildService;
    @Autowired ProductIndexManager indexManager;
    @Autowired ElasticsearchClient elasticsearch;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean ProductSearchService searchService;

    // 測試的 Spring context 不匯出指標，Metrics.counter 沒有地方記錄：自己掛一個 registry 才讀得到計數
    private static final SimpleMeterRegistry METRICS = new SimpleMeterRegistry();

    @BeforeAll
    static void attachMetrics() {
        Metrics.addRegistry(METRICS);
    }

    @AfterAll
    static void detachMetrics() {
        Metrics.removeRegistry(METRICS);
    }

    @BeforeEach
    void clearState() throws Exception {
        productRepository.deleteAllInBatch();
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
        for (String index : indexManager.indicesForAlias(ProductIndexManager.ALIAS)) {
            indexManager.deleteIndex(index);
        }
    }

    @Test
    void nameMatchRanksAheadOfDescriptionMatch() throws Exception {
        Product inDescription = save(product("收納盒", "適合收納無線藍牙耳機的小盒子", "18.00", ProductStatus.ACTIVE, null));
        Product inName = save(product("無線藍牙耳機", "通勤隨身聽", "999.00", ProductStatus.ACTIVE, null));
        reindex();

        List<String> ids = searchIds("藍牙耳機");

        assertThat(ids).containsExactly(inName.getId().toString(), inDescription.getId().toString());
    }

    @Test
    void singleCharacterAndPartialChineseWordsFindTheProduct() throws Exception {
        Product shoe = save(product("輕量運動鞋", "", "1200.00", ProductStatus.ACTIVE, null));
        Product cup = save(product("不鏽鋼保溫杯", "", "300.00", ProductStatus.ACTIVE, null));
        reindex();

        assertThat(searchIds("運動鞋")).contains(shoe.getId().toString()).doesNotContain(cup.getId().toString());
        assertThat(searchIds("保溫")).contains(cup.getId().toString());
    }

    @Test
    void skuSearchIgnoresCase() throws Exception {
        Product product = product("藍色背包", "", "500.00", ProductStatus.ACTIVE, null);
        product.setSku("ZX-900-Q");
        save(product);
        reindex();

        assertThat(searchIds("zx-900-q")).containsExactly(product.getId().toString());
    }

    @Test
    void unrelatedKeywordReturnsNoResults() throws Exception {
        save(product("不鏽鋼保溫杯", "", "300.00", ProductStatus.ACTIVE, null));
        reindex();

        mockMvc.perform(get("/api/v1/products").param("keyword", "宇宙火箭"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void storefrontNeverSeesProductsThatAreNotActive() throws Exception {
        save(product("藍牙耳機 草稿", "", "100.00", ProductStatus.DRAFT, null));
        save(product("藍牙耳機 下架", "", "100.00", ProductStatus.INACTIVE, null));
        Product active = save(product("藍牙耳機 上架", "", "100.00", ProductStatus.ACTIVE, null));
        reindex();

        assertThat(searchIds("藍牙耳機")).containsExactly(active.getId().toString());
    }

    @Test
    void indexHitsThatAreStaleInTheDatabaseAreDroppedAndPricesComeFromTheDatabase() throws Exception {
        Product archived = save(product("藍牙喇叭 舊款", "", "100.00", ProductStatus.ACTIVE, null));
        Product repriced = save(product("藍牙喇叭 新款", "", "100.00", ProductStatus.ACTIVE, null));
        reindex();
        // 索引還沒收到事件的期間：資料庫已經下架一個、改了另一個的價格
        jdbcTemplate.update("UPDATE product_service.product SET status = 'ARCHIVED' WHERE id = ?", archived.getId());
        jdbcTemplate.update("UPDATE product_service.product SET price = 777.00 WHERE id = ?", repriced.getId());
        double staleBefore = METRICS.counter("search.stale_hits").count();

        MvcResult result = mockMvc.perform(get("/api/v1/products").param("keyword", "藍牙喇叭"))
                .andExpect(status().isOk()).andReturn();

        assertThat(JsonPath.<List<String>>read(result.getResponse().getContentAsString(), "$.items[*].id"))
                .containsExactly(repriced.getId().toString());
        assertThat(JsonPath.<List<Number>>read(result.getResponse().getContentAsString(), "$.items[*].price"))
                .singleElement().satisfies(price -> assertThat(new BigDecimal(price.toString())).isEqualByComparingTo("777.00"));
        assertThat(METRICS.counter("search.stale_hits").count()).isGreaterThan(staleBefore);
    }

    @Test
    void categoryFilterCanIncludeSubcategories() throws Exception {
        Category parent = category("鞋子", "shoes", null);
        Category child = category("靴子", "boots", parent.getId());
        Product boot = save(product("防水靴", "", "900.00", ProductStatus.ACTIVE, child.getId()));
        reindex();

        assertThat(searchIds("防水靴", "categoryId", parent.getId().toString())).isEmpty();
        assertThat(searchIds("防水靴", "categoryId", parent.getId().toString(), "includeSubcategories", "true"))
                .containsExactly(boot.getId().toString());
        assertThat(searchIds("防水靴", "categoryId", child.getId().toString()))
                .containsExactly(boot.getId().toString());
    }

    @Test
    void priceRangeFilterIsInclusive() throws Exception {
        Product cheap = save(product("藍牙鍵盤 入門", "", "100.00", ProductStatus.ACTIVE, null));
        Product middle = save(product("藍牙鍵盤 標準", "", "200.00", ProductStatus.ACTIVE, null));
        save(product("藍牙鍵盤 旗艦", "", "300.00", ProductStatus.ACTIVE, null));
        reindex();

        assertThat(searchIds("藍牙鍵盤", "minPrice", "100", "maxPrice", "200"))
                .containsExactlyInAnyOrder(cheap.getId().toString(), middle.getId().toString());
    }

    @Test
    void sortByPriceFollowsTheRequestedDirection() throws Exception {
        Product cheap = save(product("藍牙滑鼠 A", "", "100.00", ProductStatus.ACTIVE, null));
        Product pricey = save(product("藍牙滑鼠 B", "", "300.00", ProductStatus.ACTIVE, null));
        reindex();

        assertThat(searchIds("藍牙滑鼠", "sortBy", "price", "sortDirection", "asc"))
                .containsExactly(cheap.getId().toString(), pricey.getId().toString());
        assertThat(searchIds("藍牙滑鼠", "sortBy", "price", "sortDirection", "desc"))
                .containsExactly(pricey.getId().toString(), cheap.getId().toString());
    }

    @Test
    void pagingWithEqualScoresAndPricesNeitherRepeatsNorSkipsProducts() throws Exception {
        for (int i = 0; i < 25; i++) {
            save(product("同名保溫杯", "", "100.00", ProductStatus.ACTIVE, null));
        }
        reindex();

        for (String sortBy : List.of("relevance", "price", "name", "createdAt")) {
            Set<String> seen = new HashSet<>();
            int count = 0;
            for (int page = 0; page < 3; page++) {
                List<String> ids = searchIds("同名保溫杯", "sortBy", sortBy, "page", String.valueOf(page), "size", "10");
                seen.addAll(ids);
                count += ids.size();
            }
            assertThat(count).as("sortBy=%s", sortBy).isEqualTo(25);
            assertThat(seen).as("sortBy=%s", sortBy).hasSize(25);
        }
    }

    @Test
    void totalsReflectTheWholeResultSet() throws Exception {
        for (int i = 0; i < 25; i++) {
            save(product("同名水壺", "", "100.00", ProductStatus.ACTIVE, null));
        }
        reindex();

        mockMvc.perform(get("/api/v1/products").param("keyword", "同名水壺").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(10));
    }

    @Test
    void resultsBeyondTheFirstTenThousandAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("keyword", "藍牙")
                        .param("page", "100").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SEARCH_RESULT_WINDOW_EXCEEDED"));
    }

    @Test
    void adminListingAndKeywordlessListingStayOnTheDatabase() throws Exception {
        save(product("藍牙音響", "", "100.00", ProductStatus.DRAFT, null));
        reindex();

        mockMvc.perform(asAdmin(get("/api/v1/products")).param("keyword", "藍牙音響"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products").param("keyword", "   ")).andExpect(status().isOk());

        verify(searchService, never()).search(any(), any());
    }

    @Test
    void searchFallsBackToTheDatabaseWhileTheIndexIsNotReady() throws Exception {
        // 沒有建立索引（別名不存在）：搜尋仍然可用，結果來自資料庫的名稱模糊比對
        Product product = save(product("藍牙耳機", "", "100.00", ProductStatus.ACTIVE, null));
        double before = METRICS.counter("search.fallback", "reason", "index_not_ready").count();

        mockMvc.perform(get("/api/v1/products").param("keyword", "藍牙"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(product.getId().toString()));

        assertThat(METRICS.counter("search.fallback", "reason", "index_not_ready").count())
                .isGreaterThan(before);
    }

    // 結果一定要來自 Elasticsearch：退回資料庫查詢時結果可能剛好一樣，不能讓測試因此誤判通過
    private List<String> searchIds(String keyword, String... extraParams) throws Exception {
        double before = METRICS.counter("search.requests", "engine", "es").count();
        MockHttpServletRequestBuilder request = get("/api/v1/products").param("keyword", keyword);
        for (int i = 0; i < extraParams.length; i += 2) {
            request.param(extraParams[i], extraParams[i + 1]);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        assertThat(METRICS.counter("search.requests", "engine", "es").count())
                .as("search for '%s' should be served by Elasticsearch", keyword).isEqualTo(before + 1);
        return new ArrayList<>(JsonPath.read(result.getResponse().getContentAsString(), "$.items[*].id"));
    }

    // 索引預設約 1 秒才會讓新寫入的文件可被搜尋；測試不等，直接 refresh
    private void reindex() throws Exception {
        ProductIndexRebuildService.RebuildResult result = rebuildService.rebuild(false);
        assertThat(result.lockAcquired()).isTrue();
        elasticsearch.indices().refresh(refresh -> refresh.index(ProductIndexManager.ALIAS));
    }

    private Product save(Product product) {
        return productRepository.saveAndFlush(product);
    }

    private Category category(String name, String slug, UUID parentId) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(slug);
        category.setParentId(parentId);
        return categoryRepository.saveAndFlush(category);
    }

    private static Product product(String name, String description, String price, ProductStatus status, UUID categoryId) {
        Product product = new Product();
        product.setSku("SKU-" + UUID.randomUUID());
        product.setName(name);
        product.setBrand("");
        product.setDescription(description);
        product.setPrice(new BigDecimal(price));
        product.setStatus(status);
        product.setCategoryId(categoryId);
        return product;
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(UserContextHeaders.USER_ID, UUID.randomUUID().toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN");
    }
}

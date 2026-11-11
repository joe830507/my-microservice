package com.mymicroservice.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.product.TestcontainersConfiguration;

// 公開的商品讀取 API：不是 ADMIN 只看得到上架中（ACTIVE）的商品，其他狀態視同不存在
// 前台（vue-customer）用；後台帶 ADMIN 的身分照舊看得到全部
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductVisibilityIntegrationTests {
    private static final String ADMIN_ID = UUID.randomUUID().toString();
    private static final String CUSTOMER_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String activeId;
    private String draftId;
    private String inactiveId;
    private String archivedId;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
        // 每次用不同的 SKU：商品詳情與 SKU 對照有 Redis 快取，避免讀到其他測試留下的資料
        String run = UUID.randomUUID().toString().substring(0, 8);
        activeId = createProduct("ACT-" + run, "Active", "ACTIVE");
        draftId = createProduct("DRF-" + run, "Draft", "DRAFT");
        inactiveId = createProduct("INA-" + run, "Inactive", "INACTIVE");
        archivedId = createProduct("ARC-" + run, "Archived", "ARCHIVED");
    }

    @Test
    void list_anonymous_onlyActive() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(activeId))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void list_customer_onlyActive() throws Exception {
        mockMvc.perform(asCustomer(get("/api/v1/products")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(activeId)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "DRAFT", "INACTIVE", "ARCHIVED" })
    void list_anonymous_statusFilterIgnored(String requested) throws Exception {
        // 傳了其他狀態也一樣只回上架中的，不會變成「看得到草稿」
        mockMvc.perform(get("/api/v1/products").param("status", requested))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(activeId)));
    }

    @Test
    void list_admin_allStatusesAndFilterWorks() throws Exception {
        mockMvc.perform(asAdmin(get("/api/v1/products")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(activeId, draftId, inactiveId, archivedId)));

        mockMvc.perform(asAdmin(get("/api/v1/products")).param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(draftId)));
    }

    @Test
    void getById_anonymous_activeVisible_othersNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/products/" + activeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(activeId));

        for (String hidden : new String[] { draftId, inactiveId, archivedId }) {
            mockMvc.perform(get("/api/v1/products/" + hidden))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        }
    }

    @Test
    void getById_customer_draftNotFound_admin_draftVisible() throws Exception {
        mockMvc.perform(asCustomer(get("/api/v1/products/" + draftId)))
                .andExpect(status().isNotFound());

        mockMvc.perform(asAdmin(get("/api/v1/products/" + draftId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void getById_adminReadCachesDraft_anonymousStillNotFound() throws Exception {
        // ADMIN 先讀，草稿進了詳情快取；可見性在讀快取之後判斷，訪客仍然看不到
        mockMvc.perform(asAdmin(get("/api/v1/products/" + draftId))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/products/" + draftId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void getBySku_sameVisibilityAsById() throws Exception {
        String activeSku = skuOf(activeId);
        String draftSku = skuOf(draftId);

        mockMvc.perform(get("/api/v1/products/sku/" + activeSku)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/sku/" + draftSku))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(asAdmin(get("/api/v1/products/sku/" + draftSku))).andExpect(status().isOk());
    }

    @Test
    void images_anonymous_draftNotFound_activeOk_adminDraftOk() throws Exception {
        mockMvc.perform(get("/api/v1/products/" + activeId + "/images"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.images", hasSize(0)));
        mockMvc.perform(get("/api/v1/products/" + draftId + "/images"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(asAdmin(get("/api/v1/products/" + draftId + "/images")))
                .andExpect(status().isOk());
    }

    // 批次查詢供 order-service 與前台購物車使用：購物車只需要知道「不能買」，
    // 所以不是上架中的商品對一般使用者只回 id 與 status，內容（sku、名稱、價格）不外流
    @Test
    void batch_anonymous_activeFull_othersOnlyIdAndStatus() throws Exception {
        Map<String, Map<String, Object>> items = batch(get("/api/v1/products/batch"));

        assertThat(items).containsOnlyKeys(activeId, draftId, inactiveId, archivedId);
        assertThat(items.get(activeId)).containsEntry("status", "ACTIVE")
                .containsEntry("name", "Active")
                .containsEntry("sku", skuOf(activeId))
                .containsKey("price");
        assertThat(items.get(activeId).get("price")).isNotNull();
        assertStatusOnly(items.get(draftId), "DRAFT");
        assertStatusOnly(items.get(inactiveId), "INACTIVE");
        assertStatusOnly(items.get(archivedId), "ARCHIVED");
    }

    @Test
    void batch_customer_sameAsAnonymous() throws Exception {
        Map<String, Map<String, Object>> items = batch(asCustomer(get("/api/v1/products/batch")));

        assertThat(items.get(activeId)).containsEntry("name", "Active");
        assertStatusOnly(items.get(draftId), "DRAFT");
    }

    @Test
    void batch_admin_allStatusesFull() throws Exception {
        Map<String, Map<String, Object>> items = batch(asAdmin(get("/api/v1/products/batch")));

        assertThat(items.get(draftId)).containsEntry("status", "DRAFT").containsEntry("name", "Draft")
                .containsEntry("sku", skuOf(draftId));
        assertThat(items.get(archivedId)).containsEntry("name", "Archived");
    }

    @Test
    void batch_adminReadCachesDraft_anonymousStillOnlyIdAndStatus() throws Exception {
        // ADMIN 先讀，草稿的完整內容進了詳情快取；內容是否外流在讀快取之後判斷
        batch(asAdmin(get("/api/v1/products/batch")));

        assertStatusOnly(batch(get("/api/v1/products/batch")).get(draftId), "DRAFT");
    }

    @Test
    void batch_missingIdsOmitted() throws Exception {
        String missing = UUID.randomUUID().toString();

        String response = mockMvc.perform(get("/api/v1/products/batch").param("ids", activeId, missing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(response, "$.products[*].id")).containsExactly(activeId);
    }

    // 帶上四個商品的 id 查詢，回傳 id → 該筆的欄位
    private Map<String, Map<String, Object>> batch(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc
                .perform(request.param("ids", activeId, draftId, inactiveId, archivedId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> products = JsonPath.read(response, "$.products");
        return products.stream().collect(Collectors.toMap(item -> (String) item.get("id"), item -> item));
    }

    private static void assertStatusOnly(Map<String, Object> item, String expectedStatus) {
        assertThat(item).containsEntry("status", expectedStatus);
        assertThat(item.get("sku")).isNull();
        assertThat(item.get("name")).isNull();
        assertThat(item.get("price")).isNull();
    }

    // 用 API 建立（DRAFT），再直接改狀態：上架要分類與主圖，這裡只關心可見性
    private String createProduct(String sku, String name, String targetStatus) throws Exception {
        String body = """
                {"sku": "%s", "name": "%s", "price": 100}
                """.formatted(sku, name);
        String response = mockMvc.perform(asAdmin(post("/api/v1/products"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        jdbcTemplate.update("UPDATE product_service.product SET status = ? WHERE id = ?::uuid", targetStatus, id);
        return id;
    }

    private String skuOf(String id) {
        return jdbcTemplate.queryForObject("SELECT sku FROM product_service.product WHERE id = ?::uuid",
                String.class, id);
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, "ADMIN");
    }

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder builder) {
        return builder.header(UserContextHeaders.USER_ID, CUSTOMER_ID)
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER");
    }
}

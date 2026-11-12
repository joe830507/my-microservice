package com.mymicroservice.product.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.mymicroservice.product.config.StorageProperties;

// GET /api/v1/products 的每一筆帶 primaryImageUrl：前台商品卡片用，不必每個商品再查一次圖片
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductListPrimaryImageIntegrationTests {
    private static final String ADMIN_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StorageProperties storageProperties;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
    }

    @Test
    void list_includesPrimaryImageUrl_orNull() throws Exception {
        String withPrimary = createActiveProduct("A");
        String withoutPrimary = createActiveProduct("B");
        String noImages = createActiveProduct("C");
        // 圖片直接寫進 DB（不需要物件儲存）：A 有主圖與一般圖，B 只有一般圖，C 沒有圖
        insertImage(withPrimary, "products/" + withPrimary + "/primary.png", true, 0);
        insertImage(withPrimary, "products/" + withPrimary + "/second.png", false, 1);
        insertImage(withoutPrimary, "products/" + withoutPrimary + "/other.png", false, 0);

        mockMvc.perform(get("/api/v1/products").param("sortBy", "name").param("sortDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(withPrimary))
                .andExpect(jsonPath("$.items[0].primaryImageUrl").value(
                        storageProperties.publicBaseUrl() + "/products/" + withPrimary + "/primary.png"))
                .andExpect(jsonPath("$.items[1].id").value(withoutPrimary))
                .andExpect(jsonPath("$.items[1].primaryImageUrl").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.items[2].id").value(noImages))
                .andExpect(jsonPath("$.items[2].primaryImageUrl").value(Matchers.nullValue()));
    }

    @Test
    void list_onlyLooksUpImagesOfCurrentPage() throws Exception {
        String first = createActiveProduct("A");
        String second = createActiveProduct("B");
        insertImage(first, "products/" + first + "/a.png", true, 0);
        insertImage(second, "products/" + second + "/b.png", true, 0);

        // 第二頁（每頁 1 筆）只有 B，網址也是 B 的
        mockMvc.perform(get("/api/v1/products").param("sortBy", "name").param("sortDirection", "asc")
                .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[0].primaryImageUrl").value(Matchers.endsWith("/b.png")));
    }

    @Test
    void list_empty_ok() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void equalPrices_haveStableOrderAcrossPages() throws Exception {
        java.util.List<String> expected = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) expected.add(createActiveProduct("Same"));
        expected.sort(String::compareTo);
        java.util.List<String> actual = new java.util.ArrayList<>();
        for (int page = 0; page < 4; page++) {
            String response = mockMvc.perform(get("/api/v1/products")
                    .param("sortBy", "price").param("sortDirection", "asc")
                    .param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            actual.addAll(JsonPath.<java.util.List<String>>read(response, "$.items[*].id"));
        }
        org.assertj.core.api.Assertions.assertThat(actual).containsExactlyElementsOf(expected);
    }

    private String createActiveProduct(String name) throws Exception {
        String body = """
                {"sku": "SKU-%s-%s", "name": "%s", "price": 100}
                """.formatted(name, UUID.randomUUID().toString().substring(0, 8), name);
        String response = mockMvc.perform(asAdmin(post("/api/v1/products"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        jdbcTemplate.update("UPDATE product_service.product SET status = 'ACTIVE' WHERE id = ?::uuid", id);
        return id;
    }

    private void insertImage(String productId, String objectKey, boolean primary, int sortOrder) {
        jdbcTemplate.update("""
                INSERT INTO product_service.product_image
                    (id, product_id, object_key, is_primary, sort_order, created_by, updated_by)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::uuid)
                """, UUID.randomUUID().toString(), productId, objectKey, primary, sortOrder, ADMIN_ID, ADMIN_ID);
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, "ADMIN");
    }
}

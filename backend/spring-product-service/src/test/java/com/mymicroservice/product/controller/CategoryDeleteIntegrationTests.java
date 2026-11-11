package com.mymicroservice.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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

// DELETE /api/v1/categories/{id}：記錄目前的行為，後台依此顯示訊息
// 底下還有子分類或商品時由 FK（ON DELETE RESTRICT）擋下，GlobalExceptionHandler 轉成 409 DATA_CONFLICT
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CategoryDeleteIntegrationTests {
    private static final String ADMIN_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
    }

    @Test
    void delete_leafCategory_removesIt() throws Exception {
        String id = createCategory(null, "Shoes", "shoes");

        mockMvc.perform(asAdmin(delete("/api/v1/categories/" + id)))
                .andExpect(status().isOk());

        assertThat(countCategories()).isZero();
    }

    @Test
    void delete_categoryWithChildren_returns409DataConflict() throws Exception {
        String parentId = createCategory(null, "Shoes", "shoes");
        createCategory(parentId, "Boots", "boots");

        mockMvc.perform(asAdmin(delete("/api/v1/categories/" + parentId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        assertThat(countCategories()).isEqualTo(2);
    }

    @Test
    void delete_categoryWithProducts_returns409DataConflict() throws Exception {
        String id = createCategory(null, "Shoes", "shoes");
        String product = """
                {"sku": "SKU-001", "name": "Runner", "categoryId": "%s", "price": 100}
                """.formatted(id);
        mockMvc.perform(asAdmin(post("/api/v1/products"))
                .contentType(MediaType.APPLICATION_JSON).content(product))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(delete("/api/v1/categories/" + id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));

        assertThat(countCategories()).isEqualTo(1);
    }

    @Test
    void delete_nonexistentCategory_returns200() throws Exception {
        // deleteById 遇到不存在的 id 不會丟例外；結果和刪除成功相同（冪等）
        mockMvc.perform(asAdmin(delete("/api/v1/categories/" + UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    private String createCategory(String parentId, String name, String slug) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        String body = """
                {"parentId": %s, "name": "%s", "slug": "%s"}
                """.formatted(parent, name, slug);
        String response = mockMvc.perform(asAdmin(post("/api/v1/categories"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private int countCategories() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product_service.category", Integer.class);
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, "ADMIN");
    }
}

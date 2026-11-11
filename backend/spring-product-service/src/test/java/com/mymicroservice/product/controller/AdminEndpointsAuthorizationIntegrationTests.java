package com.mymicroservice.product.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.product.TestcontainersConfiguration;

// 後台操作（新增、修改、刪除分類、商品、商品圖片）限 ADMIN；讀取公開
// 本機沒有 Gateway，直接帶 X-User-Id、X-User-Roles 模擬驗簽後的請求
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminEndpointsAuthorizationIntegrationTests {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String ID = "01a1bfeb-4351-7899-9b58-142decdf484e";
    private static final String IMAGE_ID = "01a1bfeb-4351-7899-9b58-142decdf484f";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
    }

    // 限 ADMIN 的 API（所有寫入，加上後台分類列表）：method、路徑、body（body 內容不重要，權限在參數驗證之前檢查）
    static Stream<Arguments> adminEndpoints() {
        String products = "/api/v1/products/" + ID;
        String images = products + "/images";
        return Stream.of(
                // 後台的分類列表（含停用）；其他讀取 API 公開
                Arguments.of("GET", "/api/v1/categories", null),
                Arguments.of("POST", "/api/v1/categories", "{}"),
                Arguments.of("PUT", "/api/v1/categories/" + ID, "{}"),
                Arguments.of("DELETE", "/api/v1/categories/" + ID, null),
                Arguments.of("POST", "/api/v1/products", "{}"),
                Arguments.of("PUT", products, "{}"),
                Arguments.of("POST", products + "/publish", null),
                Arguments.of("POST", products + "/unpublish", null),
                Arguments.of("POST", products + "/archive", null),
                Arguments.of("POST", images, "{}"),
                Arguments.of("PUT", images + "/" + IMAGE_ID, "{}"),
                Arguments.of("DELETE", images + "/" + IMAGE_ID, null),
                Arguments.of("POST", images + "/" + IMAGE_ID + "/primary", null),
                Arguments.of("PUT", images + "/order", "{}"),
                Arguments.of("POST", images + "/upload-url", "{}"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("adminEndpoints")
    void adminEndpoint_withoutIdentity_returns401(String method, String path, String body) throws Exception {
        mockMvc.perform(build(method, path, body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("adminEndpoints")
    void adminEndpoint_customer_returns403(String method, String path, String body) throws Exception {
        mockMvc.perform(build(method, path, body)
                .header(UserContextHeaders.USER_ID, USER_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("adminEndpoints")
    void adminEndpoint_admin_passesAuthorization(String method, String path, String body) throws Exception {
        // 通過權限檢查後才會遇到參數驗證或資料不存在（400、404 等），不會是 401、403
        int status = mockMvc.perform(build(method, path, body)
                .header(UserContextHeaders.USER_ID, USER_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    void createCategory_customer_doesNotCreate_admin_does() throws Exception {
        String body = "{\"name\": \"Shoes\", \"slug\": \"shoes\"}";

        mockMvc.perform(build("POST", "/api/v1/categories", body)
                .header(UserContextHeaders.USER_ID, USER_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER"))
                .andExpect(status().isForbidden());
        assertThat(countCategories()).isZero();

        mockMvc.perform(build("POST", "/api/v1/categories", body)
                .header(UserContextHeaders.USER_ID, USER_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isCreated());
        assertThat(countCategories()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/products", "/api/v1/products/" + ID, "/api/v1/products/sku/SKU-001",
            "/api/v1/products/batch?ids=" + ID, "/api/v1/products/" + ID + "/images", "/api/v1/categories/tree",
            "/api/v1/categories/" + ID, "/api/v1/categories/slug/shoes" })
    void readEndpoints_doNotRequireIdentity(String path) throws Exception {
        // 讀取不需要身分（order-service 的批次查詢也不帶身分）；不存在的資料回 404，不會是 401、403
        int status = mockMvc.perform(build("GET", path, null)).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    private static MockHttpServletRequestBuilder build(String method, String path, String body) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return builder;
    }

    private int countCategories() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM product_service.category", Integer.class);
    }
}

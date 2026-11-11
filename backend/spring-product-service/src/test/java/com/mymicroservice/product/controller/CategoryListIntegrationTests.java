package com.mymicroservice.product.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

// GET /api/v1/categories：後台用的分類列表，包含停用的分類（分類樹只有啟用中的）
// 權限（401、403）在 AdminEndpointsAuthorizationIntegrationTests
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CategoryListIntegrationTests {
    private static final String ADMIN_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
    }

    @Test
    void getAllCategories_empty_returnsEmptyList() throws Exception {
        mockMvc.perform(asAdmin(get("/api/v1/categories")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(0)));
    }

    @Test
    void getAllCategories_includesInactiveAndChildren_orderedBySortOrderThenName() throws Exception {
        String shoesId = create(null, "Shoes", "shoes", 1);
        String bootsId = create(shoesId, "Boots", "boots", 0);
        String bagsId = create(null, "Bags", "bags", 0);
        deactivate(bagsId, "Bags", "bags", 0);

        mockMvc.perform(asAdmin(get("/api/v1/categories")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(3)))
                // sortOrder 0：Bags、Boots（依 name），sortOrder 1：Shoes
                .andExpect(jsonPath("$.categories[0].id").value(bagsId))
                .andExpect(jsonPath("$.categories[0].active").value(false))
                .andExpect(jsonPath("$.categories[0].parentId").value(nullValue()))
                .andExpect(jsonPath("$.categories[0].version").value(1))
                .andExpect(jsonPath("$.categories[1].id").value(bootsId))
                .andExpect(jsonPath("$.categories[1].parentId").value(shoesId))
                .andExpect(jsonPath("$.categories[1].active").value(true))
                .andExpect(jsonPath("$.categories[2].id").value(shoesId))
                .andExpect(jsonPath("$.categories[2].sortOrder").value(1))
                .andExpect(jsonPath("$.categories[2].description").value("Shoes description"))
                .andExpect(jsonPath("$.categories[2].createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.categories[2].updatedAt").value(notNullValue()));
    }

    @Test
    void getAllCategories_childOfInactiveParent_isStillListed() throws Exception {
        // 分類樹會把停用分類的整個子樹藏起來；後台列表不會
        String shoesId = create(null, "Shoes", "shoes", 0);
        String bootsId = create(shoesId, "Boots", "boots", 0);
        deactivate(shoesId, "Shoes", "shoes", 0);

        mockMvc.perform(asAdmin(get("/api/v1/categories")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.id == '" + bootsId + "')].active").value(true))
                .andExpect(jsonPath("$.categories[?(@.id == '" + shoesId + "')].active").value(false));

        mockMvc.perform(get("/api/v1/categories/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roots", hasSize(0)));
    }

    private String create(String parentId, String name, String slug, int sortOrder) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        String body = """
                {"parentId": %s, "name": "%s", "slug": "%s", "description": "%s description", "sortOrder": %d}
                """.formatted(parent, name, slug, name, sortOrder);
        String response = mockMvc.perform(asAdmin(post("/api/v1/categories"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    // 剛建立的分類 version 是 0
    private void deactivate(String id, String name, String slug, int sortOrder) throws Exception {
        String body = """
                {"parentId": null, "name": "%s", "slug": "%s", "description": "%s description",
                 "sortOrder": %d, "active": false, "version": 0}
                """.formatted(name, slug, name, sortOrder);
        mockMvc.perform(asAdmin(put("/api/v1/categories/" + id))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, "ADMIN");
    }
}

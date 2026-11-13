package com.mymicroservice.product.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

// GET /api/v1/products?categoryId=...&includeSubcategories=true：連同子孫分類的商品一起查
// 分類：鞋子 → 靴子 → 雨靴；鞋子 → 涼鞋（停用）；包包（另一棵樹）
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductListSubcategoryIntegrationTests {
    private static final String ADMIN_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String shoes;
    private String boots;
    private String rainBoots;
    private String sandals;
    private String bags;

    private String shoesProduct;
    private String bootsProduct;
    private String rainBootsProduct;
    private String sandalsProduct;
    private String bagsProduct;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @BeforeEach
    void setUp() throws Exception {
        redis.delete(com.mymicroservice.product.cache.CategoryTreeCache.LINKS_KEY);
        jdbcTemplate.execute("TRUNCATE product_service.category CASCADE");
        jdbcTemplate.execute("TRUNCATE product_service.product CASCADE");
        shoes = createCategory(null, "Shoes", "shoes", true);
        boots = createCategory(shoes, "Boots", "boots", true);
        rainBoots = createCategory(boots, "Rain Boots", "rain-boots", true);
        sandals = createCategory(shoes, "Sandals", "sandals", false);
        bags = createCategory(null, "Bags", "bags", true);

        shoesProduct = createActiveProduct(shoes);
        bootsProduct = createActiveProduct(boots);
        rainBootsProduct = createActiveProduct(rainBoots);
        sandalsProduct = createActiveProduct(sandals);
        bagsProduct = createActiveProduct(bags);
    }

    @Test
    void includeSubcategories_returnsWholeSubtree() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("categoryId", shoes).param("includeSubcategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id",
                        containsInAnyOrder(shoesProduct, bootsProduct, rainBootsProduct, sandalsProduct)))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void includeSubcategories_fromMiddleOfTree() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("categoryId", boots).param("includeSubcategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(bootsProduct, rainBootsProduct)));
    }

    @Test
    void withoutFlag_onlyThatCategory() throws Exception {
        // 預設不含子分類，和原本的行為一樣（後台的分類篩選）
        mockMvc.perform(get("/api/v1/products").param("categoryId", shoes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(shoesProduct)));

        mockMvc.perform(get("/api/v1/products").param("categoryId", shoes).param("includeSubcategories", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(shoesProduct)));
    }

    @Test
    void includeSubcategories_leafCategory_sameAsExact() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("categoryId", bags).param("includeSubcategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(bagsProduct)));
    }

    @Test
    void includeSubcategories_unknownCategory_empty() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("categoryId", UUID.randomUUID().toString())
                .param("includeSubcategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void includeSubcategories_combinedWithVisibility() throws Exception {
        // 子分類的商品不是上架中：訪客看不到，ADMIN 看得到
        jdbcTemplate.update("UPDATE product_service.product SET status = 'DRAFT' WHERE id = ?::uuid", rainBootsProduct);

        mockMvc.perform(get("/api/v1/products").param("categoryId", boots).param("includeSubcategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(bootsProduct)));

        mockMvc.perform(asAdmin(get("/api/v1/products")).param("categoryId", boots)
                .param("includeSubcategories", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(bootsProduct, rainBootsProduct)));
    }

    private String createCategory(String parentId, String name, String slug, boolean active) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        String body = """
                {"parentId": %s, "name": "%s", "slug": "%s", "active": %s}
                """.formatted(parent, name, slug, active);
        String response = mockMvc.perform(asAdmin(post("/api/v1/categories"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String createActiveProduct(String categoryId) throws Exception {
        String body = """
                {"sku": "SKU-%s", "name": "Item", "categoryId": "%s", "price": 100}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), categoryId);
        String response = mockMvc.perform(asAdmin(post("/api/v1/products"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(response, "$.id");
        jdbcTemplate.update("UPDATE product_service.product SET status = 'ACTIVE' WHERE id = ?::uuid", id);
        return id;
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, "ADMIN");
    }
}

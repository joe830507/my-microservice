package com.mymicroservice.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;

import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.inventory.TestcontainersConfiguration;

// PUT /api/v1/inventory/{sku}：本機沒有 Gateway，直接帶 X-User-Id、X-User-Roles 模擬驗簽後的請求
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StockControllerSetIntegrationTests {
    private static final UUID SYSTEM = new UUID(0L, 0L);
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE inventory_service.reservation, inventory_service.stock CASCADE");
    }

    // ---------- 成功 ----------

    @Test
    void setStock_newSku_creates201AndRecordsAdminAsCreator() throws Exception {
        setAsAdmin("SKU-NEW", "{\"available\": 10}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("SKU-NEW"))
                .andExpect(jsonPath("$.available").value(10))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        Map<String, Object> row = findStock("SKU-NEW");
        assertThat(row.get("available")).isEqualTo(10);
        assertThat(row.get("reserved")).isEqualTo(0);
        assertThat(row.get("created_by")).isEqualTo(ADMIN_ID);
        assertThat(row.get("updated_by")).isEqualTo(ADMIN_ID);
    }

    @Test
    void setStock_existingSku_updates200AndKeepsReserved() throws Exception {
        insertStock("SKU-001", 5, 3);

        setAsAdmin("SKU-001", "{\"available\": 20}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(20))
                .andExpect(jsonPath("$.reserved").value(3));

        Map<String, Object> row = findStock("SKU-001");
        assertThat(row.get("available")).isEqualTo(20);
        assertThat(row.get("reserved")).isEqualTo(3);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("created_by")).isEqualTo(SYSTEM);
        assertThat(row.get("updated_by")).isEqualTo(ADMIN_ID);
    }

    @Test
    void setStock_zero_isAllowed() throws Exception {
        insertStock("SKU-001", 5, 0);

        setAsAdmin("SKU-001", "{\"available\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0));
    }

    @Test
    void setStock_reservedInBody_isIgnored() throws Exception {
        // reserved 只能由預扣流程改變
        insertStock("SKU-001", 5, 3);

        setAsAdmin("SKU-001", "{\"available\": 8, \"reserved\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reserved").value(3));
        assertThat(findStock("SKU-001").get("reserved")).isEqualTo(3);
    }

    // ---------- 權限 ----------

    @Test
    void setStock_missingUserId_returns401() throws Exception {
        mockMvc.perform(put("/api/v1/inventory/SKU-001")
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\": 1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertThat(countStock()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "CUSTOMER", "", "admin" })
    void setStock_notAdmin_returns403(String roles) throws Exception {
        mockMvc.perform(put("/api/v1/inventory/SKU-001")
                .header(UserContextHeaders.USER_ID, ADMIN_ID.toString())
                .header(UserContextHeaders.USER_ROLES, roles)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\": 1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(countStock()).isZero();
    }

    @Test
    void setStock_notAdminWithInvalidBody_returns403Not400() throws Exception {
        mockMvc.perform(put("/api/v1/inventory/SKU-001")
                .header(UserContextHeaders.USER_ID, ADMIN_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"available\": -1}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 參數驗證 ----------

    @ParameterizedTest
    @ValueSource(strings = { "{\"available\": -1}", "{}", "{\"available\": null}", "{\"available\": \"ten\"}",
            "{\"available\": 1.5}", "{\"available\": 3000000000}" })
    void setStock_invalidBody_returns400(String body) throws Exception {
        setAsAdmin("SKU-001", body)
                .andExpect(status().isBadRequest());
        assertThat(countStock()).isZero();
    }

    @Test
    void setStock_negative_returnsValidationFailedWithField() throws Exception {
        // 方法同時有路徑參數約束與 @Valid body，改由方法層級驗證處理；detail 仍要帶欄位名稱
        setAsAdmin("SKU-001", "{\"available\": -1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("available: ")));
    }

    @Test
    void setStock_skuLongerThan64_returns400() throws Exception {
        setAsAdmin("A".repeat(65), "{\"available\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("sku: ")));
        assertThat(countStock()).isZero();
    }

    @Test
    void setStock_blankSku_returns400() throws Exception {
        setAsAdmin(" ", "{\"available\": 1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(countStock()).isZero();
    }

    // ---------- helpers ----------

    private ResultActions setAsAdmin(String sku, String body) throws Exception {
        return mockMvc.perform(put("/api/v1/inventory/{sku}", sku)
                .header(UserContextHeaders.USER_ID, ADMIN_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void insertStock(String sku, int available, int reserved) {
        jdbcTemplate.update("""
                INSERT INTO inventory_service.stock (sku, available, reserved, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                """, sku, available, reserved, SYSTEM, SYSTEM);
    }

    private Map<String, Object> findStock(String sku) {
        return jdbcTemplate.queryForMap("SELECT * FROM inventory_service.stock WHERE sku = ?", sku);
    }

    private int countStock() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_service.stock", Integer.class);
    }
}

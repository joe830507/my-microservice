package com.mymicroservice.inventory.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import com.mymicroservice.inventory.TestcontainersConfiguration;

// GET /api/v1/inventory/{sku}：不需要身分 header
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StockControllerGetIntegrationTests {
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE inventory_service.reservation, inventory_service.stock CASCADE");
        jdbcTemplate.update("""
                INSERT INTO inventory_service.stock (sku, available, reserved, created_by, updated_by)
                VALUES ('SKU-001', 7, 3, ?, ?)
                """, SYSTEM, SYSTEM);
    }

    @Test
    void getStock_existing_returnsQuantitiesWithoutIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/SKU-001"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sku").value("SKU-001"))
                .andExpect(jsonPath("$.available").value(7))
                .andExpect(jsonPath("$.reserved").value(3))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                // 稽核欄位、version 不對外
                .andExpect(jsonPath("$.createdBy").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void getStock_skuIsCaseSensitive() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/sku-001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }

    @Test
    void getStock_unknownSku_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/NO-SUCH-SKU"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"))
                .andExpect(jsonPath("$.detail", containsString("NO-SUCH-SKU")));
    }

    @Test
    void getStock_skuLongerThan64_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/" + "A".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void getStock_skuOf64_isAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/" + "A".repeat(64)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
    }
}

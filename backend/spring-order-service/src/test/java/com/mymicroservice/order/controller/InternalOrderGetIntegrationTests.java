package com.mymicroservice.order.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.mymicroservice.order.OrderTestData;
import com.mymicroservice.order.TestcontainersConfiguration;

// GET /internal/v1/orders/{id}：給 payment-service，不需要身分 header
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalOrderGetIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
    }

    @Test
    void getOrder_returnsAmountAndStatusWithoutIdentity() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT", 3, Instant.now());

        mockMvc.perform(get("/internal/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.userId").value(ALICE.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(300.00))
                // 只回付款需要的欄位
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void getOrder_cancelled_returnsStatusSoPaymentCanRefuse() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "CANCELLED");

        mockMvc.perform(get("/internal/v1/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void getOrder_unknown_returns404() throws Exception {
        mockMvc.perform(get("/internal/v1/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getOrder_invalidId_returns400() throws Exception {
        mockMvc.perform(get("/internal/v1/orders/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}

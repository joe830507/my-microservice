package com.mymicroservice.payment.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.payment.PaymentTestData;
import com.mymicroservice.payment.TestcontainersConfiguration;

// GET /api/v1/payments/{id}：本人或 ADMIN 才看得到
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentControllerGetIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orderId;
    private UUID paymentId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE payment_service.payment");
        orderId = UUID.randomUUID();
        paymentId = PaymentTestData.insertPendingPayment(jdbcTemplate, orderId, ALICE);
    }

    @Test
    void getPayment_owner_returnsStatus() throws Exception {
        getAs(paymentId, ALICE, "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.amount").value(399.98))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.orderNotification").doesNotExist());
    }

    @Test
    void getPayment_otherUser_returns404() throws Exception {
        getAs(paymentId, BOB, "CUSTOMER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ADMIN", "CUSTOMER,ADMIN" })
    void getPayment_admin_canSeeAnyPayment(String roles) throws Exception {
        getAs(paymentId, BOB, roles).andExpect(status().isOk());
    }

    @Test
    void getPayment_unknown_returns404() throws Exception {
        getAs(UUID.randomUUID(), ALICE, "CUSTOMER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void getPayment_missingUserId_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{id}", paymentId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPayment_invalidId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/payments/not-a-uuid").header(UserContextHeaders.USER_ID, ALICE.toString()))
                .andExpect(status().isBadRequest());
    }

    private ResultActions getAs(UUID id, UUID userId, String roles) throws Exception {
        return mockMvc.perform(get("/api/v1/payments/{id}", id)
                .header(UserContextHeaders.USER_ID, userId.toString())
                .header(UserContextHeaders.USER_ROLES, roles));
    }
}

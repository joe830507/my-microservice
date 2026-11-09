package com.mymicroservice.order.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.order.OrderTestData;
import com.mymicroservice.order.TestcontainersConfiguration;

// GET /api/v1/orders：一般使用者只看得到自己的；ADMIN 看全部
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerListIntegrationTests {
    private static final String ORDERS_URL = "/api/v1/orders";
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID alice1;
    private UUID alice2;
    private UUID alice3;
    private UUID bob1;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
        // 建立時間固定，排序結果可預期：alice3 > bob1 > alice2 > alice1
        alice1 = insert(ALICE, "PAID", "2026-01-01T00:00:00Z");
        alice2 = insert(ALICE, "PENDING_PAYMENT", "2026-02-01T00:00:00Z");
        bob1 = insert(BOB, "PENDING_PAYMENT", "2026-02-15T00:00:00Z");
        alice3 = insert(ALICE, "CANCELLED", "2026-03-01T00:00:00Z");
    }

    // ---------- 一般使用者 ----------

    @Test
    void getOrders_customer_seesOnlyOwnOrdersNewestFirst() throws Exception {
        listAs(get(ORDERS_URL), ALICE, "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(alice3.toString(), alice2.toString(), alice1.toString())))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void getOrders_itemFields_areSummaryOnly() throws Exception {
        listAs(get(ORDERS_URL).param("status", "PAID"), ALICE, "CUSTOMER")
                .andExpect(jsonPath("$.items[0].id").value(alice1.toString()))
                .andExpect(jsonPath("$.items[0].userId").value(ALICE.toString()))
                .andExpect(jsonPath("$.items[0].status").value("PAID"))
                .andExpect(jsonPath("$.items[0].totalAmount").value(100.00))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-01-01T00:00:00Z"))
                // 列表不帶明細
                .andExpect(jsonPath("$.items[0].items").doesNotExist());
    }

    @Test
    void getOrders_customerPassingUserId_stillSeesOnlyOwn() throws Exception {
        // 一般使用者帶別人的 userId 會被忽略，不能藉此看別人的訂單
        listAs(get(ORDERS_URL).param("userId", BOB.toString()), ALICE, "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].id", contains(alice3.toString(), alice2.toString(), alice1.toString())));
    }

    @Test
    void getOrders_customerWithNoOrders_returnsEmpty() throws Exception {
        listAs(get(ORDERS_URL), UUID.randomUUID(), "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getOrders_filterByStatus() throws Exception {
        listAs(get(ORDERS_URL).param("status", "PENDING_PAYMENT"), ALICE, "CUSTOMER")
                .andExpect(jsonPath("$.items[*].id", contains(alice2.toString())));
    }

    @Test
    void getOrders_pagination() throws Exception {
        listAs(get(ORDERS_URL).param("page", "1").param("size", "2"), ALICE, "CUSTOMER")
                .andExpect(jsonPath("$.items[*].id", contains(alice1.toString())))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    // ---------- ADMIN ----------

    @Test
    void getOrders_admin_seesAllOrders() throws Exception {
        listAs(get(ORDERS_URL), ADMIN, "ADMIN")
                .andExpect(jsonPath("$.items[*].id",
                        contains(alice3.toString(), bob1.toString(), alice2.toString(), alice1.toString())));
    }

    @Test
    void getOrders_adminFilterByUserAndStatus() throws Exception {
        listAs(get(ORDERS_URL).param("userId", ALICE.toString()).param("status", "PENDING_PAYMENT"), ADMIN, "ADMIN")
                .andExpect(jsonPath("$.items[*].id", contains(alice2.toString())));
        listAs(get(ORDERS_URL).param("userId", BOB.toString()), ADMIN, "CUSTOMER,ADMIN")
                .andExpect(jsonPath("$.items[*].id", contains(bob1.toString())));
    }

    // ---------- 身分與驗證 ----------

    @Test
    void getOrders_missingUserId_returns401() throws Exception {
        mockMvc.perform(get(ORDERS_URL).header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({ "page,-1", "size,0", "size,101", "status,SHIPPING", "userId,not-a-uuid" })
    void getOrders_invalidParam_returns400(String name, String value) throws Exception {
        listAs(get(ORDERS_URL).param(name, value), ADMIN, "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(name + ":")));
    }

    // ---------- helpers ----------

    private UUID insert(UUID userId, String status, String createdAt) {
        return OrderTestData.insertOrder(jdbcTemplate, userId, status, 1, Instant.parse(createdAt));
    }

    private ResultActions listAs(MockHttpServletRequestBuilder request, UUID userId, String roles) throws Exception {
        return mockMvc.perform(request
                .header(UserContextHeaders.USER_ID, userId.toString())
                .header(UserContextHeaders.USER_ROLES, roles));
    }
}

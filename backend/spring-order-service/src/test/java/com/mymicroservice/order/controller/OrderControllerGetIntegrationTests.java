package com.mymicroservice.order.controller;

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
import com.mymicroservice.order.OrderTestData;
import com.mymicroservice.order.TestcontainersConfiguration;

// GET /api/v1/orders/{id}：本人或 ADMIN 才看得到
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerGetIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceOrder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
        aliceOrder = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT", 2, java.time.Instant.now());
    }

    @Test
    void getOrder_owner_returnsDetail() throws Exception {
        getAs(aliceOrder, ALICE, "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceOrder.toString()))
                .andExpect(jsonPath("$.userId").value(ALICE.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(200.00))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("SHOE-001"))
                .andExpect(jsonPath("$.items[0].productName").value("Running Shoe"))
                .andExpect(jsonPath("$.items[0].qty").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                // 內部同步用的欄位不對外
                .andExpect(jsonPath("$.reservationStatus").doesNotExist())
                .andExpect(jsonPath("$.reservationSynced").doesNotExist());
    }

    @Test
    void getOrder_otherUsersOrder_returns404LikeNonexistent() throws Exception {
        // 不能用 403：會讓人知道這個 id 存在
        getAs(aliceOrder, BOB, "CUSTOMER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void getOrder_nonexistent_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();

        getAs(unknown, ALICE, "CUSTOMER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("order not found: " + unknown + "."));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ADMIN", "CUSTOMER,ADMIN" })
    void getOrder_admin_canSeeAnyOrder(String roles) throws Exception {
        getAs(aliceOrder, BOB, roles)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ALICE.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "admin", "ADMINISTRATOR", "" })
    void getOrder_notExactlyAdmin_cannotSeeOthersOrder(String roles) throws Exception {
        getAs(aliceOrder, BOB, roles)
                .andExpect(status().isNotFound());
    }

    @Test
    void getOrder_missingRolesHeader_stillWorksForOwner() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", aliceOrder)
                .header(UserContextHeaders.USER_ID, ALICE.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void getOrder_missingUserId_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/orders/{id}", aliceOrder)
                .header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void getOrder_invalidId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/orders/not-a-uuid")
                .header(UserContextHeaders.USER_ID, ALICE.toString()))
                .andExpect(status().isBadRequest());
    }

    private ResultActions getAs(UUID orderId, UUID userId, String roles) throws Exception {
        return mockMvc.perform(get("/api/v1/orders/{id}", orderId)
                .header(UserContextHeaders.USER_ID, userId.toString())
                .header(UserContextHeaders.USER_ROLES, roles));
    }
}

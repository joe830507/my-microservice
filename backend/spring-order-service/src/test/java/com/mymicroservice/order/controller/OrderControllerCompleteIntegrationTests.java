package com.mymicroservice.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

// POST /api/v1/orders/{id}/complete：限 ADMIN，只有 SHIPPED 能完成；不呼叫其他服務
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerCompleteIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
    }

    @Test
    void complete_shipped_marksCompleted() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "SHIPPED");

        completeAs(orderId, ADMIN, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(1));

        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("updated_by")).isEqualTo(ADMIN);
        assertThat(row.get("version")).isEqualTo(1L);
        // 完成不影響預扣
        assertThat(row.get("reservation_status")).isEqualTo("CONFIRMED");

    }

    @Test
    void complete_alreadyCompleted_returns200WithoutChanging() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "SHIPPED");
        completeAs(orderId, ADMIN, "ADMIN").andExpect(status().isOk());
        Object completedAt = findOrder(orderId).get("completed_at");

        completeAs(orderId, ADMIN, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("completed_at")).isEqualTo(completedAt);
    }

    @Test
    void shipThenComplete_keepsBothTimestamps() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PAID");

        mockMvc.perform(post("/api/v1/orders/{id}/ship", orderId)
                .header(UserContextHeaders.USER_ID, ADMIN.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isOk());
        completeAs(orderId, ADMIN, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.shippedAt").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        assertThat(findOrder(orderId).get("version")).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_PAYMENT", "PAID", "CANCELLED" })
    void complete_notShipped_returns409(String status) throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, status);

        completeAs(orderId, ADMIN, "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_COMPLETABLE"))
                .andExpect(jsonPath("$.detail").value(
                        "order " + orderId + " cannot be completed in status " + status + "."));

        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("completed_at")).isNull();
    }

    @Test
    void complete_customer_returns403AndChangesNothing() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "SHIPPED");

        // 自己的訂單也不能自己完成
        completeAs(orderId, ALICE, "CUSTOMER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(findOrder(orderId).get("status")).isEqualTo("SHIPPED");
    }

    @Test
    void complete_missingUserId_returns401() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "SHIPPED");

        mockMvc.perform(post("/api/v1/orders/{id}/complete", orderId)
                .header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(findOrder(orderId).get("status")).isEqualTo("SHIPPED");
    }

    @Test
    void complete_nonexistent_returns404() throws Exception {
        completeAs(UUID.randomUUID(), ADMIN, "ADMIN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void complete_concurrently_allReturn200AndCompleteOnce() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "SHIPPED");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return completeAs(orderId, ADMIN, "ADMIN").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        // 只有一個請求真的完成（version 只加 1）
        assertThat(findOrder(orderId).get("version")).isEqualTo(1L);
    }

    // ---------- helpers ----------

    private ResultActions completeAs(UUID orderId, UUID userId, String roles) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{id}/complete", orderId)
                .header(UserContextHeaders.USER_ID, userId.toString())
                .header(UserContextHeaders.USER_ROLES, roles));
    }

    private Map<String, Object> findOrder(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM order_service.orders WHERE id = ?", orderId);
    }
}

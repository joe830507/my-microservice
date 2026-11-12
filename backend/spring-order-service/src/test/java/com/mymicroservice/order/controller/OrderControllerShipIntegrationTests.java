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

// POST /api/v1/orders/{id}/ship：限 ADMIN，只有 PAID 能出貨；不呼叫其他服務
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerShipIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.outbox_event, order_service.orders CASCADE");
    }

    @Test
    void ship_paid_marksShipped() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PAID");

        shipAs(orderId, ADMIN, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shippedAt").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(1));

        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("status")).isEqualTo("SHIPPED");
        assertThat(row.get("shipped_at")).isNotNull();
        assertThat(row.get("updated_by")).isEqualTo(ADMIN);
        assertThat(row.get("version")).isEqualTo(1L);
        // 出貨不影響預扣
        assertThat(row.get("reservation_status")).isEqualTo("CONFIRMED");
        assertThat(countShippedEvents(orderId)).isEqualTo(1);

    }

    @Test
    void ship_alreadyShipped_returns200WithoutChanging() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PAID");
        shipAs(orderId, ADMIN, "ADMIN").andExpect(status().isOk());
        Object shippedAt = findOrder(orderId).get("shipped_at");

        shipAs(orderId, ADMIN, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("shipped_at")).isEqualTo(shippedAt);
        assertThat(countShippedEvents(orderId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_PAYMENT", "CANCELLED", "COMPLETED" })
    void ship_notPaid_returns409(String status) throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, status);

        shipAs(orderId, ADMIN, "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_SHIPPABLE"))
                .andExpect(jsonPath("$.detail").value(
                        "order " + orderId + " cannot be shipped in status " + status + "."));

        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("shipped_at")).isNull();
        assertThat(countShippedEvents(orderId)).isZero();
    }

    @Test
    void ship_customer_returns403AndChangesNothing() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PAID");

        // 自己的訂單也不能自己出貨
        shipAs(orderId, ALICE, "CUSTOMER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(findOrder(orderId).get("status")).isEqualTo("PAID");
    }

    @Test
    void ship_missingUserId_returns401() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PAID");

        mockMvc.perform(post("/api/v1/orders/{id}/ship", orderId)
                .header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(findOrder(orderId).get("status")).isEqualTo("PAID");
    }

    @Test
    void ship_nonexistent_returns404() throws Exception {
        shipAs(UUID.randomUUID(), ADMIN, "ADMIN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void ship_concurrently_allReturn200AndShipOnce() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PAID");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return shipAs(orderId, ADMIN, "ADMIN").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        // 只有一個請求真的出貨（version 只加 1）
        assertThat(findOrder(orderId).get("version")).isEqualTo(1L);
        assertThat(countShippedEvents(orderId)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private ResultActions shipAs(UUID orderId, UUID userId, String roles) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{id}/ship", orderId)
                .header(UserContextHeaders.USER_ID, userId.toString())
                .header(UserContextHeaders.USER_ROLES, roles));
    }

    private Map<String, Object> findOrder(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM order_service.orders WHERE id = ?", orderId);
    }

    private int countShippedEvents(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event "
                + "WHERE event_type = 'OrderShipped' AND aggregate_id = ?", Integer.class, orderId.toString());
    }
}

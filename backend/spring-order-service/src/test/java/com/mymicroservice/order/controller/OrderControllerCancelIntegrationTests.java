package com.mymicroservice.order.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.order.OrderTestData;
import com.mymicroservice.order.TestcontainersConfiguration;

// POST /api/v1/orders/{id}/cancel：inventory-service 用 WireMock 模擬
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerCancelIntegrationTests {
    private static final String RELEASE_PATTERN = "/internal/v1/inventory/reservations/[0-9a-f-]+/release";
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @RegisterExtension
    static final WireMockExtension INVENTORY_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("order.services.inventory-uri", INVENTORY_SERVICE::baseUrl);
        registry.add("order.services.read-timeout", () -> "1s");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
        stubRelease(200);
    }

    @Test
    void cancel_pending_cancelsAndWritesCancellationEvent() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        cancelAs(orderId, ALICE, "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        INVENTORY_SERVICE.verify(0, postRequestedFor(releaseUrl(orderId)));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = \'OrderCancelled\'", Long.class, orderId.toString())).isEqualTo(1);
        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("reservation_status")).isEqualTo("RELEASED");

        assertThat(row.get("updated_by")).isEqualTo(ALICE);
        assertThat(row.get("version")).isEqualTo(1L);
    }

    @Test
    void cancel_inventoryDown_stillCancelsAndWritesEvent() throws Exception {
        // 取消不能因為 inventory 掛掉而失敗；沒釋放成功的由排程重試
        stubRelease(500);
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        cancelAs(orderId, ALICE, "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("reservation_status")).isEqualTo("RELEASED");

    }

    @Test
    void cancel_alreadyCancelled_returns200WithoutCallingInventory() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "CANCELLED");

        cancelAs(orderId, ALICE, "CUSTOMER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void cancel_again_doesNotDuplicateEvent() throws Exception {
        stubRelease(500);
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");
        cancelAs(orderId, ALICE, "CUSTOMER").andExpect(status().isOk());

        stubRelease(200);
        cancelAs(orderId, ALICE, "CUSTOMER").andExpect(status().isOk());

        INVENTORY_SERVICE.verify(0, postRequestedFor(releaseUrl(orderId)));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = \'OrderCancelled\'", Long.class, orderId.toString())).isEqualTo(1);

    }

    @ParameterizedTest
    @ValueSource(strings = { "PAID", "SHIPPED", "COMPLETED" })
    void cancel_notPending_returns409(String status) throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, status);

        cancelAs(orderId, ALICE, "CUSTOMER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.detail").value(
                        "order " + orderId + " cannot be cancelled in status " + status + "."));

        assertThat(findOrder(orderId).get("status")).isEqualTo(status);
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void cancel_otherUsersOrder_returns404AndChangesNothing() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        cancelAs(orderId, BOB, "CUSTOMER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        assertThat(findOrder(orderId).get("status")).isEqualTo("PENDING_PAYMENT");
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void cancel_admin_canCancelOthersOrder() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");
        UUID admin = UUID.randomUUID();

        cancelAs(orderId, admin, "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(findOrder(orderId).get("updated_by")).isEqualTo(admin);
    }

    @Test
    void cancel_nonexistent_returns404() throws Exception {
        cancelAs(UUID.randomUUID(), ALICE, "CUSTOMER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void cancel_missingUserId_returns401() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId))
                .andExpect(status().isUnauthorized());
        assertThat(findOrder(orderId).get("status")).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void cancel_concurrently_allReturn200AndCancelOnce() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return cancelAs(orderId, ALICE, "CUSTOMER").andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }

        // 只有一個請求真的取消（version 只加 1）；釋放至少呼叫一次（重複呼叫 inventory 也沒關係）
        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("version")).isEqualTo(1L);

        assertThat(INVENTORY_SERVICE.findAll(postRequestedFor(releaseUrl(orderId)))).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = 'OrderCancelled'", Long.class, orderId.toString())).isEqualTo(1);
    }

    // ---------- helpers ----------

    private static void stubRelease(int status) {
        INVENTORY_SERVICE.stubFor(WireMock.post(urlPathMatching(RELEASE_PATTERN))
                .willReturn(aResponse().withStatus(status)));
    }

    private static com.github.tomakehurst.wiremock.matching.UrlPattern releaseUrl(UUID orderId) {
        return urlEqualTo("/internal/v1/inventory/reservations/" + orderId + "/release");
    }

    private ResultActions cancelAs(UUID orderId, UUID userId, String roles) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                .header(UserContextHeaders.USER_ID, userId.toString())
                .header(UserContextHeaders.USER_ROLES, roles));
    }

    private Map<String, Object> findOrder(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM order_service.orders WHERE id = ?", orderId);
    }
}

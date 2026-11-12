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
import java.util.concurrent.Callable;
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

// POST /internal/v1/orders/{id}/paid：payment-service 通知付款成功；inventory-service 用 WireMock 模擬
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalOrderPaidIntegrationTests {
    private static final String RESERVATIONS = "/internal/v1/inventory/reservations/";
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID SYSTEM = new UUID(0L, 0L);

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
        stubInventory(200);
    }

    @Test
    void paid_pending_marksPaidAndWritesPaidEvent() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        paid(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("PAID"));

        INVENTORY_SERVICE.verify(0, postRequestedFor(urlEqualTo(RESERVATIONS + orderId + "/confirm")));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = \'OrderPaid\'", Long.class, orderId.toString())).isEqualTo(1);
        Map<String, Object> row = findOrder(orderId);
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(row.get("paid_at")).isNotNull();
        assertThat(row.get("reservation_status")).isEqualTo("CONFIRMED");

        // 內部呼叫沒有使用者身分，記為系統使用者
        assertThat(row.get("updated_by")).isEqualTo(SYSTEM);
    }

    @Test
    void paid_inventoryDown_stillMarksPaidAndWritesEvent() throws Exception {
        // 付款已經成功，不能因為 inventory 掛掉就回錯誤；確認由排程重試
        stubInventory(500);
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        paid(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

    }

    @Test
    void paid_twice_returns200AndWritesOneEvent() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        paid(orderId).andExpect(status().isOk());
        paid(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        INVENTORY_SERVICE.verify(0, postRequestedFor(urlEqualTo(RESERVATIONS + orderId + "/confirm")));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = \'OrderPaid\'", Long.class, orderId.toString())).isEqualTo(1);
    }

    @Test
    void paid_again_doesNotDuplicateEvent() throws Exception {
        stubInventory(500);
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");
        paid(orderId).andExpect(status().isOk());

        stubInventory(200);
        paid(orderId).andExpect(status().isOk());

        INVENTORY_SERVICE.verify(0, postRequestedFor(urlEqualTo(RESERVATIONS + orderId + "/confirm")));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = \'OrderPaid\'", Long.class, orderId.toString())).isEqualTo(1);

    }

    @ParameterizedTest
    @ValueSource(strings = { "SHIPPED", "COMPLETED" })
    void paid_alreadyBeyondPaid_returns200WithoutCallingInventory(String status) throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, status);

        paid(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status));

        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void paid_cancelled_returns409AndStaysCancelled() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "CANCELLED");

        paid(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));

        assertThat(findOrder(orderId).get("status")).isEqualTo("CANCELLED");
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void paid_unknown_returns404() throws Exception {
        paid(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void paidAndCancel_concurrently_exactlyOneWins() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbcTemplate, ALICE, "PENDING_PAYMENT");

        List<Integer> statuses = runConcurrently(
                () -> paid(orderId),
                () -> mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId)
                        .header(UserContextHeaders.USER_ID, ALICE.toString())));

        // 一個 200、一個 409，訂單狀態和庫存呼叫一致
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Map<String, Object> row = findOrder(orderId);
        if ("PAID".equals(row.get("status"))) {
            assertThat(row.get("reservation_status")).isEqualTo("CONFIRMED");
            INVENTORY_SERVICE.verify(0, postRequestedFor(urlPathMatching(".*/release")));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = \'OrderPaid\'", Long.class, orderId.toString())).isEqualTo(1);
        } else {
            assertThat(row.get("status")).isEqualTo("CANCELLED");
            assertThat(row.get("reservation_status")).isEqualTo("RELEASED");
            INVENTORY_SERVICE.verify(0, postRequestedFor(urlPathMatching(".*/confirm")));
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE aggregate_id = ? AND event_type = 'OrderCancelled'", Long.class, orderId.toString())).isEqualTo(1);
        }
    }

    // ---------- helpers ----------

    @SafeVarargs
    private List<Integer> runConcurrently(Callable<ResultActions>... requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.length);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<ResultActions> request : requests) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return request.call().andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }

    // 確認與釋放都回同一個狀態碼
    private static void stubInventory(int status) {
        INVENTORY_SERVICE.stubFor(WireMock.post(urlPathMatching(RESERVATIONS + "[0-9a-f-]+/(confirm|release)"))
                .willReturn(aResponse().withStatus(status)));
    }

    private ResultActions paid(UUID orderId) throws Exception {
        return mockMvc.perform(post("/internal/v1/orders/{id}/paid", orderId));
    }

    private Map<String, Object> findOrder(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM order_service.orders WHERE id = ?", orderId);
    }
}

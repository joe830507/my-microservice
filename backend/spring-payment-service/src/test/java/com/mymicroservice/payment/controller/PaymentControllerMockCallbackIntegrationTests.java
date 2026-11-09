package com.mymicroservice.payment.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.payment.PaymentTestData;
import com.mymicroservice.payment.TestcontainersConfiguration;

// POST /api/v1/payments/{id}/mock-callback：order-service 用 WireMock 模擬
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentControllerMockCallbackIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @RegisterExtension
    static final WireMockExtension ORDER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("payment.services.order-uri", ORDER_SERVICE::baseUrl);
        registry.add("payment.services.read-timeout", () -> "1s");
    }

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
        stubPaid(200, null);
    }

    // ---------- 成功 ----------

    @Test
    void success_marksSucceededAndNotifiesOrder() throws Exception {
        callback(paymentId, ALICE, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        ORDER_SERVICE.verify(1, postRequestedFor(paidUrl()));
        Map<String, Object> row = findPayment();
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("order_notification")).isEqualTo("DONE");
        assertThat(row.get("completed_at")).isNotNull();
        assertThat(row.get("version")).isEqualTo(1L);
    }

    @Test
    void success_twice_returns200AndNotifiesOnce() throws Exception {
        callback(paymentId, ALICE, "SUCCESS").andExpect(status().isOk());
        callback(paymentId, ALICE, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        ORDER_SERVICE.verify(1, postRequestedFor(paidUrl()));
    }

    @Test
    void success_orderServiceDown_staysPendingNotificationAndRetriesOnNextCallback() throws Exception {
        // 付款結果不能因為 order-service 掛掉而失敗；通知留給排程或下一次回呼重試
        stubPaid(500, null);
        callback(paymentId, ALICE, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(findPayment().get("order_notification")).isEqualTo("PENDING");

        stubPaid(200, null);
        callback(paymentId, ALICE, "SUCCESS").andExpect(status().isOk());

        ORDER_SERVICE.verify(2, postRequestedFor(paidUrl()));
        assertThat(findPayment().get("order_notification")).isEqualTo("DONE");
    }

    @Test
    void success_orderAlreadyCancelled_marksRejected() throws Exception {
        // 錢收了但訂單已取消：記成 REJECTED（之後要退款），不再重試
        stubPaid(409, "ORDER_ALREADY_CANCELLED");

        callback(paymentId, ALICE, "SUCCESS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(findPayment().get("order_notification")).isEqualTo("REJECTED");
    }

    // ---------- 失敗 ----------

    @Test
    void failed_marksFailedWithoutNotifyingOrder() throws Exception {
        callback(paymentId, ALICE, "FAILED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        Map<String, Object> row = findPayment();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("order_notification")).isNull();
    }

    @Test
    void failed_twice_returns200() throws Exception {
        callback(paymentId, ALICE, "FAILED").andExpect(status().isOk());
        callback(paymentId, ALICE, "FAILED").andExpect(status().isOk());
    }

    @Test
    void oppositeResult_returns409AndKeepsFirstResult() throws Exception {
        callback(paymentId, ALICE, "SUCCESS").andExpect(status().isOk());

        callback(paymentId, ALICE, "FAILED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_COMPLETED"))
                .andExpect(jsonPath("$.detail").value(
                        "payment " + paymentId + " has already been completed as SUCCEEDED."));

        assertThat(findPayment().get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void successAndFailed_concurrently_exactlyOneWins() throws Exception {
        List<Integer> statuses = runConcurrently(
                () -> callback(paymentId, ALICE, "SUCCESS"),
                () -> callback(paymentId, ALICE, "FAILED"));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Map<String, Object> row = findPayment();
        if ("SUCCEEDED".equals(row.get("status"))) {
            ORDER_SERVICE.verify(1, postRequestedFor(paidUrl()));
        } else {
            assertThat(row.get("status")).isEqualTo("FAILED");
            ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    // ---------- 權限與驗證 ----------

    @Test
    void otherUser_returns404AndChangesNothing() throws Exception {
        callback(paymentId, BOB, "SUCCESS")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(findPayment().get("status")).isEqualTo("PENDING");
        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void admin_canCompleteOthersPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{id}/mock-callback", paymentId)
                .header(UserContextHeaders.USER_ID, BOB.toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\": \"SUCCESS\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void missingUserId_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments/{id}/mock-callback", paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\": \"SUCCESS\"}"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"result\": null}", "{\"result\": \"MAYBE\"}", "{\"result\": \"success\"}" })
    void invalidBody_returns400(String body) throws Exception {
        mockMvc.perform(post("/api/v1/payments/{id}/mock-callback", paymentId)
                .header(UserContextHeaders.USER_ID, ALICE.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest());

        assertThat(findPayment().get("status")).isEqualTo("PENDING");
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

    private com.github.tomakehurst.wiremock.matching.UrlPattern paidUrl() {
        return urlEqualTo("/internal/v1/orders/" + orderId + "/paid");
    }

    private void stubPaid(int status, String code) {
        ORDER_SERVICE.stubFor(WireMock.post(urlEqualTo("/internal/v1/orders/" + orderId + "/paid"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", status == 200 ? MediaType.APPLICATION_JSON_VALUE
                                : MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                        .withBody(code == null ? "{}" : "{\"status\": %d, \"code\": \"%s\"}".formatted(status, code))));
    }

    private ResultActions callback(UUID id, UUID userId, String result) throws Exception {
        return mockMvc.perform(post("/api/v1/payments/{id}/mock-callback", id)
                .header(UserContextHeaders.USER_ID, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\": \"%s\"}".formatted(result)));
    }

    private Map<String, Object> findPayment() {
        return jdbcTemplate.queryForMap("SELECT * FROM payment_service.payment WHERE id = ?", paymentId);
    }
}

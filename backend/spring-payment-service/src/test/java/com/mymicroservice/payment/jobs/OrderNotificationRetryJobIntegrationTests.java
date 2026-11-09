package com.mymicroservice.payment.jobs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mymicroservice.payment.PaymentTestData;
import com.mymicroservice.payment.TestcontainersConfiguration;

// 排程：重試通知 order-service；直接呼叫 run()，不等 cron 觸發
// 打開 cron 只為了確認有註冊；時間設成不會在測試期間觸發
@SpringBootTest(properties = "app.order-notification-retry.cron=0 0 0 1 1 *")
@Import(TestcontainersConfiguration.class)
class OrderNotificationRetryJobIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();

    @RegisterExtension
    static final WireMockExtension ORDER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("payment.services.order-uri", ORDER_SERVICE::baseUrl);
        registry.add("payment.services.read-timeout", () -> "1s");
    }

    @Autowired
    private OrderNotificationRetryJob job;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE payment_service.payment");
        stubPaid(200, null);
    }

    @Test
    void run_notifiesPendingNotificationsAndMarksDone() {
        UUID orderId = UUID.randomUUID();
        UUID paymentId = insert(orderId, "SUCCEEDED", "PENDING", Instant.now().minusSeconds(300));

        job.run();

        ORDER_SERVICE.verify(1, postRequestedFor(urlEqualTo("/internal/v1/orders/" + orderId + "/paid")));
        assertThat(notification(paymentId)).isEqualTo("DONE");
    }

    @Test
    void run_orderCancelled_marksRejected() {
        stubPaid(409, "ORDER_ALREADY_CANCELLED");
        UUID paymentId = insert(UUID.randomUUID(), "SUCCEEDED", "PENDING", Instant.now().minusSeconds(300));

        job.run();

        assertThat(notification(paymentId)).isEqualTo("REJECTED");
    }

    @Test
    void run_orderServiceStillDown_leavesPendingForNextRun() {
        stubPaid(500, null);
        UUID paymentId = insert(UUID.randomUUID(), "SUCCEEDED", "PENDING", Instant.now().minusSeconds(300));

        job.run();
        assertThat(notification(paymentId)).isEqualTo("PENDING");

        stubPaid(200, null);
        job.run();
        assertThat(notification(paymentId)).isEqualTo("DONE");
    }

    @Test
    void run_skipsRecentlyUpdatedAndAlreadyNotified() {
        // 剛成功的（回呼請求可能正在通知）、已通知的、被拒絕的、還沒付款的都不動
        insert(UUID.randomUUID(), "SUCCEEDED", "PENDING", Instant.now().minusSeconds(10));
        insert(UUID.randomUUID(), "SUCCEEDED", "DONE", Instant.now().minusSeconds(300));
        insert(UUID.randomUUID(), "SUCCEEDED", "REJECTED", Instant.now().minusSeconds(300));
        insert(UUID.randomUUID(), "PENDING", null, Instant.now().minusSeconds(300));

        job.run();

        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void job_isScheduled() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .map(scheduledTask -> scheduledTask.getTask())
                .filteredOn(task -> task instanceof CronTask)
                .hasSize(1);
    }

    private static void stubPaid(int status, String code) {
        ORDER_SERVICE.stubFor(WireMock.post(urlPathMatching("/internal/v1/orders/[0-9a-f-]+/paid"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", status == 200 ? MediaType.APPLICATION_JSON_VALUE
                                : MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                        .withBody(code == null ? "{}" : "{\"status\": %d, \"code\": \"%s\"}".formatted(status, code))));
    }

    private UUID insert(UUID orderId, String status, String orderNotification, Instant updatedAt) {
        return PaymentTestData.insertPayment(jdbcTemplate, orderId, ALICE, status, orderNotification, updatedAt);
    }

    private String notification(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT order_notification FROM payment_service.payment WHERE id = ?",
                String.class, paymentId);
    }
}

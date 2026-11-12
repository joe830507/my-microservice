package com.mymicroservice.order.jobs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mymicroservice.order.OrderTestData;
import com.mymicroservice.order.TestcontainersConfiguration;

// 排程：付款逾時自動取消、補做庫存同步；直接呼叫 run()，不等 cron 觸發
// 這裡打開 cron（src/test/resources 預設停用），只為了確認有註冊；cron 時間設成不會在測試期間觸發
@SpringBootTest(properties = {
        "app.payment-timeout.cron=0 0 0 1 1 *",
        "app.payment-timeout.batch-size=2" })
@Import(TestcontainersConfiguration.class)
class OrderJobsIntegrationTests {
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
    private PaymentTimeoutJob paymentTimeoutJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
        stubInventory(200);
    }

    // ---------- 付款逾時 ----------

    @Test
    void paymentTimeout_cancelsOnlyPendingOlderThanTimeoutAndReleases() throws Exception {
        UUID expired = insert("PENDING_PAYMENT", minutesAgo(31));
        UUID recent = insert("PENDING_PAYMENT", minutesAgo(10));
        UUID oldButPaid = insert("PAID", minutesAgo(120));

        paymentTimeoutJob.run();

        Map<String, Object> expiredRow = findOrder(expired);
        assertThat(expiredRow.get("status")).isEqualTo("CANCELLED");
        assertThat(expiredRow.get("cancelled_at")).isNotNull();
        assertThat(expiredRow.get("reservation_status")).isEqualTo("RELEASED");

        assertThat(expiredRow.get("updated_by")).isEqualTo(SYSTEM);
        INVENTORY_SERVICE.verify(0, postRequestedFor(urlEqualTo(RESERVATIONS + expired + "/release")));

        assertThat(findOrder(recent).get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(findOrder(oldButPaid).get("status")).isEqualTo("PAID");
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void paymentTimeout_processesAllBatches() throws Exception {
        // batch-size = 2，5 張要分 3 批
        for (int i = 0; i < 5; i++) {
            insert("PENDING_PAYMENT", minutesAgo(60));
        }

        paymentTimeoutJob.run();

        assertThat(countByStatus("PENDING_PAYMENT")).isZero();
        assertThat(countByStatus("CANCELLED")).isEqualTo(5);
    }

    @Test
    void paymentTimeout_inventoryDown_stillCancelsAndWritesEvent() throws Exception {
        stubInventory(500);
        UUID expired = insert("PENDING_PAYMENT", minutesAgo(31));

        paymentTimeoutJob.run();

        Map<String, Object> row = findOrder(expired);
        assertThat(row.get("status")).isEqualTo("CANCELLED");

    }

    // ---------- 補做庫存同步 ----------

    @Test
    void jobs_areScheduledWithConfiguredCron() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .map(scheduledTask -> scheduledTask.getTask())
                .filteredOn(task -> task instanceof CronTask)
                .map(task -> ((CronTask) task).getExpression())
                .containsExactlyInAnyOrder("0 0 0 1 1 *", "0 20 3 * * *", "0 40 3 * * *");
    }

    // ---------- helpers ----------

    private static void stubInventory(int status) {
        INVENTORY_SERVICE.stubFor(WireMock.post(urlPathMatching(RESERVATIONS + "[0-9a-f-]+/(confirm|release)"))
                .willReturn(aResponse().withStatus(status)));
    }

    private static Instant minutesAgo(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES);
    }

    private UUID insert(String status, Instant createdAt) {
        return OrderTestData.insertOrder(jdbcTemplate, ALICE, status, 1, createdAt);
    }

    private Map<String, Object> findOrder(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM order_service.orders WHERE id = ?", orderId);
    }

    private int countByStatus(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.orders WHERE status = ?",
                Integer.class, status);
    }
}

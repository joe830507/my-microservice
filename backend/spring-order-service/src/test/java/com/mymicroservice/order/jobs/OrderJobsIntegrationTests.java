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
        "app.reservation-sync.cron=0 0 0 1 1 *",
        "app.payment-timeout.batch-size=2",
        "app.reservation-sync.batch-size=2" })
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
    private ReservationSyncJob reservationSyncJob;
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
        assertThat(expiredRow.get("reservation_synced")).isEqualTo(true);
        assertThat(expiredRow.get("updated_by")).isEqualTo(SYSTEM);
        INVENTORY_SERVICE.verify(1, postRequestedFor(urlEqualTo(RESERVATIONS + expired + "/release")));

        assertThat(findOrder(recent).get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(findOrder(oldButPaid).get("status")).isEqualTo("PAID");
        INVENTORY_SERVICE.verify(1, anyRequestedFor(anyUrl()));
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
    void paymentTimeout_inventoryDown_stillCancelsAndLeavesUnsynced() throws Exception {
        stubInventory(500);
        UUID expired = insert("PENDING_PAYMENT", minutesAgo(31));

        paymentTimeoutJob.run();

        Map<String, Object> row = findOrder(expired);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("reservation_synced")).isEqualTo(false);
    }

    // ---------- 補做庫存同步 ----------

    @Test
    void reservationSync_retriesReleaseAndConfirmForUnsyncedOrders() throws Exception {
        UUID cancelled = insertUnsynced("CANCELLED", "RELEASED", minutesAgo(5));
        UUID paid = insertUnsynced("PAID", "CONFIRMED", minutesAgo(5));

        reservationSyncJob.run();

        INVENTORY_SERVICE.verify(1, postRequestedFor(urlEqualTo(RESERVATIONS + cancelled + "/release")));
        INVENTORY_SERVICE.verify(1, postRequestedFor(urlEqualTo(RESERVATIONS + paid + "/confirm")));
        assertThat(findOrder(cancelled).get("reservation_synced")).isEqualTo(true);
        assertThat(findOrder(paid).get("reservation_synced")).isEqualTo(true);
    }

    @Test
    void reservationSync_skipsRecentlyUpdatedOrders() throws Exception {
        // 剛改完狀態、請求本身可能正在呼叫 inventory，不搶著做
        UUID justCancelled = insertUnsynced("CANCELLED", "RELEASED", Instant.now().minusSeconds(10));

        reservationSyncJob.run();

        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        assertThat(findOrder(justCancelled).get("reservation_synced")).isEqualTo(false);
    }

    @Test
    void reservationSync_inventoryStillDown_leavesUnsyncedForNextRun() throws Exception {
        stubInventory(500);
        UUID cancelled = insertUnsynced("CANCELLED", "RELEASED", minutesAgo(5));

        reservationSyncJob.run();
        assertThat(findOrder(cancelled).get("reservation_synced")).isEqualTo(false);

        stubInventory(200);
        reservationSyncJob.run();
        assertThat(findOrder(cancelled).get("reservation_synced")).isEqualTo(true);
    }

    @Test
    void reservationSync_ignoresSyncedOrders() throws Exception {
        insert("CANCELLED", minutesAgo(5));
        insert("PAID", minutesAgo(5));

        reservationSyncJob.run();

        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void jobs_areScheduledWithConfiguredCron() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .map(scheduledTask -> scheduledTask.getTask())
                .filteredOn(task -> task instanceof CronTask)
                .hasSize(2);
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

    // 已經改了狀態、但還沒成功通知 inventory 的訂單
    private UUID insertUnsynced(String status, String reservationStatus, Instant updatedAt) {
        UUID orderId = insert(status, updatedAt);
        jdbcTemplate.update("""
                UPDATE order_service.orders SET reservation_status = ?, reservation_synced = false WHERE id = ?
                """, reservationStatus, orderId);
        return orderId;
    }

    private Map<String, Object> findOrder(UUID orderId) {
        return jdbcTemplate.queryForMap("SELECT * FROM order_service.orders WHERE id = ?", orderId);
    }

    private int countByStatus(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.orders WHERE status = ?",
                Integer.class, status);
    }
}

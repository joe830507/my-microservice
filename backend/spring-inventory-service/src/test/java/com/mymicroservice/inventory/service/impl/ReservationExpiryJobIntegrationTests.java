package com.mymicroservice.inventory.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.mymicroservice.inventory.TestcontainersConfiguration;

// 排程釋放逾時的預扣；直接呼叫 run()，不等 cron 觸發
// batch-size 設小，驗證會分批處理到全部
@SpringBootTest(properties = "app.reservation-expiry.batch-size=2")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReservationExpiryJobIntegrationTests {
    private static final String RESERVATIONS_URL = "/internal/v1/inventory/reservations";
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private ReservationExpiryJob job;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE inventory_service.reservation, inventory_service.stock CASCADE");
        jdbcTemplate.update("""
                INSERT INTO inventory_service.stock (sku, available, reserved, created_by, updated_by)
                VALUES ('SKU-A', 100, 0, ?, ?)
                """, SYSTEM, SYSTEM);
    }

    @Test
    void run_releasesOnlyReservedOlderThanTtl() throws Exception {
        UUID expired = reserve(3, "2 hours");
        UUID recent = reserve(4, "10 minutes");
        UUID expiredButConfirmed = reserve(5, "2 hours");
        confirm(expiredButConfirmed);
        assertStock(88, 7);

        job.run();

        assertThat(reservationStatus(expired)).isEqualTo("RELEASED");
        assertThat(reservationStatus(recent)).isEqualTo("RESERVED");
        assertThat(reservationStatus(expiredButConfirmed)).isEqualTo("CONFIRMED");
        // 只有 expired 的 3 件還回 available
        assertStock(91, 4);
    }

    @Test
    void run_processesAllBatches() throws Exception {
        // batch-size = 2，5 筆要分 3 批
        for (int i = 0; i < 5; i++) {
            reserve(1, "2 hours");
        }

        job.run();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_service.reservation WHERE status = 'RESERVED'", Integer.class))
                .isZero();
        assertStock(100, 0);
    }

    @Test
    void run_withNothingExpired_changesNothing() throws Exception {
        UUID recent = reserve(2, "59 minutes");

        job.run();

        assertThat(reservationStatus(recent)).isEqualTo("RESERVED");
        assertStock(98, 2);
    }

    @Test
    void releasedByJob_blocksLateConfirm() throws Exception {
        // 付款成功通知在預扣被釋放之後才到：確認回 409，order-service 會知道庫存已經還回去
        UUID orderId = reserve(3, "2 hours");
        job.run();

        mockMvc.perform(post(RESERVATIONS_URL + "/{orderId}/confirm", orderId))
                .andExpect(status().isConflict());
        assertStock(100, 0);
    }

    @Test
    void job_isScheduledWithConfiguredCron() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .map(scheduledTask -> scheduledTask.getTask())
                .filteredOn(task -> task instanceof CronTask)
                .map(task -> ((CronTask) task).getExpression())
                .contains("0 */5 * * * *");
    }

    // ---------- helpers ----------

    // 經由預扣 API 建立，再把建立時間往前推
    private UUID reserve(int qty, String age) throws Exception {
        UUID orderId = UUID.randomUUID();
        mockMvc.perform(post(RESERVATIONS_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": \"%s\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": %d}]}"
                        .formatted(orderId, qty)))
                .andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE inventory_service.reservation SET created_at = now() - ?::interval WHERE order_id = ?",
                age, orderId);
        return orderId;
    }

    private void confirm(UUID orderId) throws Exception {
        mockMvc.perform(post(RESERVATIONS_URL + "/{orderId}/confirm", orderId)).andExpect(status().isOk());
    }

    private String reservationStatus(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM inventory_service.reservation WHERE order_id = ?",
                String.class, orderId);
    }

    private void assertStock(int available, int reserved) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT available, reserved FROM inventory_service.stock WHERE sku = 'SKU-A'");
        assertThat(row.get("available")).as("available").isEqualTo(available);
        assertThat(row.get("reserved")).as("reserved").isEqualTo(reserved);
    }
}

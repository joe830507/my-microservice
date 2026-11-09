package com.mymicroservice.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mymicroservice.inventory.TestcontainersConfiguration;

// POST /internal/v1/inventory/reservations/{orderId}/confirm：付款成功後把預扣轉成正式扣除
// 先經由預扣 API 建立預扣，確保 stock 與 reservation 的數字一致
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalReservationConfirmIntegrationTests {
    private static final String RESERVATIONS_URL = "/internal/v1/inventory/reservations";
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE inventory_service.reservation, inventory_service.stock CASCADE");
        insertStock("SKU-A", 10, 0);
        insertStock("SKU-B", 5, 0);
    }

    @Test
    void confirm_reserved_deductsReservedOnlyAndReturns200() throws Exception {
        UUID orderId = reserve("SKU-A", 3, "SKU-B", 2);
        assertStock("SKU-A", 7, 3);

        confirm(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.items[0].qty").value(3))
                .andExpect(jsonPath("$.items[1].sku").value("SKU-B"));

        // available 在預扣時已經扣過，確認只減少 reserved
        assertStock("SKU-A", 7, 0);
        assertStock("SKU-B", 3, 0);
        assertThat(reservationStatus(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void confirm_bumpsVersionsAndAuditsAsSystem() throws Exception {
        UUID orderId = reserve("SKU-A", 1, null, 0);

        confirm(orderId).andExpect(status().isOk());

        Map<String, Object> reservation = jdbcTemplate.queryForMap(
                "SELECT version, updated_by FROM inventory_service.reservation WHERE order_id = ?", orderId);
        assertThat(reservation.get("version")).isEqualTo(1L);
        assertThat(reservation.get("updated_by")).isEqualTo(SYSTEM);
        // 預扣 + 1、確認 + 1
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM inventory_service.stock WHERE sku = 'SKU-A'", Long.class)).isEqualTo(2L);
    }

    @Test
    void confirm_twice_returns200AndDeductsOnce() throws Exception {
        UUID orderId = reserve("SKU-A", 3, null, 0);
        reserve("SKU-A", 2, null, 0); // 另一張還沒付款的訂單

        confirm(orderId).andExpect(status().isOk());
        confirm(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 只扣掉這張訂單的 3，另一張的 2 還在 reserved
        assertStock("SKU-A", 5, 2);
    }

    @Test
    void confirm_released_returns409AndChangesNothing() throws Exception {
        UUID orderId = reserve("SKU-A", 3, null, 0);
        // 模擬已釋放：狀態 RELEASED，數量還回 available（Step 6 的釋放 API 會做同樣的事）
        jdbcTemplate.update("UPDATE inventory_service.reservation SET status = 'RELEASED' WHERE order_id = ?",
                orderId);
        jdbcTemplate.update("UPDATE inventory_service.stock SET available = 10, reserved = 0 WHERE sku = 'SKU-A'");

        confirm(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_RELEASED"))
                .andExpect(jsonPath("$.detail", containsString(orderId.toString())));

        assertStock("SKU-A", 10, 0);
        assertThat(reservationStatus(orderId)).isEqualTo("RELEASED");
    }

    @Test
    void confirm_releasedWithoutItems_returns409() throws Exception {
        // 預扣還沒到就先釋放留下的紀錄
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO inventory_service.reservation (order_id, status, created_by, updated_by)
                VALUES (?, 'RELEASED', ?, ?)
                """, orderId, SYSTEM, SYSTEM);

        confirm(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_RELEASED"));
    }

    @Test
    void confirm_unknownOrder_returns404() throws Exception {
        UUID orderId = UUID.randomUUID();

        confirm(orderId)
                .andExpect(status().isNotFound())
                .andExpect(content -> assertThat(content.getResponse().getContentType())
                        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"))
                .andExpect(jsonPath("$.detail", containsString(orderId.toString())));

        assertThat(countReservations()).isZero();
    }

    @Test
    void confirm_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(RESERVATIONS_URL + "/not-a-uuid/confirm"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void confirm_inconsistentReserved_returns500AndRollsBack() throws Exception {
        // reserved 被改得比預扣明細少（資料不一致）：不能只確認一半
        UUID orderId = reserve("SKU-A", 3, "SKU-B", 2);
        jdbcTemplate.update("UPDATE inventory_service.stock SET reserved = 1 WHERE sku = 'SKU-B'");

        confirm(orderId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(reservationStatus(orderId)).isEqualTo("RESERVED");
        assertStock("SKU-A", 7, 3);
    }

    @Test
    void confirm_concurrently_deductsOnce() throws Exception {
        UUID orderId = reserve("SKU-A", 3, null, 0);
        reserve("SKU-A", 2, null, 0);

        List<Integer> statuses = runConcurrently(5, () -> confirm(orderId));

        assertThat(statuses).containsOnly(200);
        assertStock("SKU-A", 5, 2);
    }

    // ---------- helpers ----------

    private List<Integer> runConcurrently(int count, Callable<ResultActions> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
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

    // 經由預扣 API 建立一張新訂單的預扣；skuB 為 null 時只有一個品項
    private UUID reserve(String skuA, int qtyA, String skuB, int qtyB) throws Exception {
        UUID orderId = UUID.randomUUID();
        String items = "{\"sku\": \"%s\", \"qty\": %d}".formatted(skuA, qtyA)
                + (skuB == null ? "" : ", {\"sku\": \"%s\", \"qty\": %d}".formatted(skuB, qtyB));
        mockMvc.perform(post(RESERVATIONS_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": \"%s\", \"items\": [%s]}".formatted(orderId, items)))
                .andExpect(status().isCreated());
        return orderId;
    }

    private ResultActions confirm(UUID orderId) throws Exception {
        return mockMvc.perform(post(RESERVATIONS_URL + "/{orderId}/confirm", orderId));
    }

    private void insertStock(String sku, int available, int reserved) {
        jdbcTemplate.update("""
                INSERT INTO inventory_service.stock (sku, available, reserved, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                """, sku, available, reserved, SYSTEM, SYSTEM);
    }

    private void assertStock(String sku, int available, int reserved) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT available, reserved FROM inventory_service.stock WHERE sku = ?", sku);
        assertThat(row.get("available")).as(sku + " available").isEqualTo(available);
        assertThat(row.get("reserved")).as(sku + " reserved").isEqualTo(reserved);
    }

    private String reservationStatus(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM inventory_service.reservation WHERE order_id = ?",
                String.class, orderId);
    }

    private int countReservations() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_service.reservation", Integer.class);
    }
}

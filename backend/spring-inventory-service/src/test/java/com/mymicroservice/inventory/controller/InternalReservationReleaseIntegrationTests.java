package com.mymicroservice.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
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

// POST /internal/v1/inventory/reservations/{orderId}/release：取消或逾時時把預扣的數量還回去
// 先經由預扣 API 建立預扣，確保 stock 與 reservation 的數字一致
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalReservationReleaseIntegrationTests {
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
    void release_reserved_returnsQuantitiesAndReturns200() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-A", 3, "SKU-B", 2).andExpect(status().isCreated());
        assertStock("SKU-A", 7, 3);

        release(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.items[1].sku").value("SKU-B"));

        assertStock("SKU-A", 10, 0);
        assertStock("SKU-B", 5, 0);
        assertThat(reservationStatus(orderId)).isEqualTo("RELEASED");
    }

    @Test
    void release_twice_returns200AndReturnsOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-A", 3, null, 0).andExpect(status().isCreated());
        reserve(UUID.randomUUID(), "SKU-A", 2, null, 0).andExpect(status().isCreated());

        release(orderId).andExpect(status().isOk());
        release(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        // 只還這張訂單的 3，另一張的 2 還在 reserved
        assertStock("SKU-A", 8, 2);
    }

    @Test
    void release_releasedQuantity_canBeReservedByOthers() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-B", 5, null, 0).andExpect(status().isCreated());
        reserve(UUID.randomUUID(), "SKU-B", 1, null, 0).andExpect(status().isConflict());

        release(orderId).andExpect(status().isOk());

        reserve(UUID.randomUUID(), "SKU-B", 5, null, 0).andExpect(status().isCreated());
    }

    @Test
    void release_confirmed_returns409AndChangesNothing() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-A", 3, null, 0).andExpect(status().isCreated());
        mockMvc.perform(post(RESERVATIONS_URL + "/{orderId}/confirm", orderId)).andExpect(status().isOk());

        release(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_CONFIRMED"))
                .andExpect(jsonPath("$.detail", containsString(orderId.toString())));

        assertStock("SKU-A", 7, 0);
        assertThat(reservationStatus(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    void release_unknownOrder_recordsReleasedWithoutItemsAndReturns200() throws Exception {
        UUID orderId = UUID.randomUUID();

        release(orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.items", empty()));

        assertThat(reservationStatus(orderId)).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by FROM inventory_service.reservation WHERE order_id = ?", UUID.class, orderId))
                .isEqualTo(SYSTEM);
        assertStock("SKU-A", 10, 0);
    }

    @Test
    void release_unknownOrderTwice_returns200() throws Exception {
        UUID orderId = UUID.randomUUID();

        release(orderId).andExpect(status().isOk());
        release(orderId).andExpect(status().isOk());

        assertThat(countReservations()).isEqualTo(1);
    }

    @Test
    void release_beforeReserveArrives_blocksLateReservation() throws Exception {
        // order-service 預扣逾時，不知道有沒有成功就取消；預扣請求之後才送達
        UUID orderId = UUID.randomUUID();
        release(orderId).andExpect(status().isOk());

        reserve(orderId, "SKU-A", 3, null, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_RELEASED"));

        assertStock("SKU-A", 10, 0);
    }

    @Test
    void release_thenConfirm_returns409() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-A", 3, null, 0).andExpect(status().isCreated());
        release(orderId).andExpect(status().isOk());

        mockMvc.perform(post(RESERVATIONS_URL + "/{orderId}/confirm", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_RELEASED"));

        assertStock("SKU-A", 10, 0);
    }

    @Test
    void release_invalidOrderId_returns400() throws Exception {
        mockMvc.perform(post(RESERVATIONS_URL + "/not-a-uuid/release"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void release_inconsistentReserved_returns500AndRollsBack() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-A", 3, "SKU-B", 2).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE inventory_service.stock SET reserved = 1 WHERE sku = 'SKU-B'");

        release(orderId)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(reservationStatus(orderId)).isEqualTo("RESERVED");
        assertStock("SKU-A", 7, 3);
    }

    @Test
    void release_concurrently_returnsOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-A", 3, null, 0).andExpect(status().isCreated());

        List<Integer> statuses = runConcurrently(5, () -> release(orderId));

        assertThat(statuses).containsOnly(200);
        assertStock("SKU-A", 10, 0);
    }

    @Test
    void releaseAndConfirm_concurrently_exactlyOneWins() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, "SKU-A", 3, null, 0).andExpect(status().isCreated());

        List<Integer> statuses = runConcurrently(2, () -> release(orderId),
                () -> mockMvc.perform(post(RESERVATIONS_URL + "/{orderId}/confirm", orderId)));

        // 一個 200、另一個 409；庫存只會是其中一種結果
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        String status = reservationStatus(orderId);
        if (status.equals("RELEASED")) {
            assertStock("SKU-A", 10, 0);
        } else {
            assertThat(status).isEqualTo("CONFIRMED");
            assertStock("SKU-A", 7, 0);
        }
    }

    // ---------- helpers ----------

    // 所有請求準備好後同時送出；requests 只有一個時重複 count 次，否則依序各送一次
    @SafeVarargs
    private List<Integer> runConcurrently(int count, Callable<ResultActions>... requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<ResultActions> request = requests[requests.length == 1 ? 0 : i];
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

    // skuB 為 null 時只有一個品項
    private ResultActions reserve(UUID orderId, String skuA, int qtyA, String skuB, int qtyB) throws Exception {
        String items = "{\"sku\": \"%s\", \"qty\": %d}".formatted(skuA, qtyA)
                + (skuB == null ? "" : ", {\"sku\": \"%s\", \"qty\": %d}".formatted(skuB, qtyB));
        return mockMvc.perform(post(RESERVATIONS_URL).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": \"%s\", \"items\": [%s]}".formatted(orderId, items)));
    }

    private ResultActions release(UUID orderId) throws Exception {
        return mockMvc.perform(post(RESERVATIONS_URL + "/{orderId}/release", orderId));
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

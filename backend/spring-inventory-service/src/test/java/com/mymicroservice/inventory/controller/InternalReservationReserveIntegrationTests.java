package com.mymicroservice.inventory.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mymicroservice.inventory.TestcontainersConfiguration;

// POST /internal/v1/inventory/reservations：內部 API，不需要身分 header
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalReservationReserveIntegrationTests {
    private static final String URL = "/internal/v1/inventory/reservations";
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE inventory_service.reservation, inventory_service.stock CASCADE");
        insertStock("SKU-A", 10, 0);
        insertStock("SKU-B", 5, 1);
        insertStock("SKU-C", 1, 0);
    }

    // ---------- 成功 ----------

    @Test
    void reserve_enoughStock_deductsAndReturns201() throws Exception {
        UUID orderId = UUID.randomUUID();

        reserve(orderId, item("SKU-B", 2), item("SKU-A", 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                // 依 SKU 排序
                .andExpect(jsonPath("$.items[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.items[0].qty").value(3))
                .andExpect(jsonPath("$.items[1].sku").value("SKU-B"))
                .andExpect(jsonPath("$.items[1].qty").value(2));

        assertStock("SKU-A", 7, 3);
        assertStock("SKU-B", 3, 3);
        assertStock("SKU-C", 1, 0);
        assertThat(reservationStatus(orderId)).isEqualTo("RESERVED");
        assertThat(reservationItems(orderId)).containsExactlyInAnyOrderEntriesOf(Map.of("SKU-A", 3, "SKU-B", 2));
    }

    @Test
    void reserve_bumpsStockVersionAndAuditsAsSystem() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, item("SKU-A", 1)).andExpect(status().isCreated());

        Map<String, Object> stock = findStock("SKU-A");
        // version + 1：同時在編輯的後台 PUT 會收到 409，不會把扣掉的數量蓋回去
        assertThat(stock.get("version")).isEqualTo(1L);
        assertThat(stock.get("updated_by")).isEqualTo(SYSTEM);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by FROM inventory_service.reservation WHERE order_id = ?", UUID.class, orderId))
                .isEqualTo(SYSTEM);
    }

    @Test
    void reserve_exactlyAllAvailable_succeeds() throws Exception {
        reserve(UUID.randomUUID(), item("SKU-C", 1))
                .andExpect(status().isCreated());

        assertStock("SKU-C", 0, 1);
    }

    @Test
    void reserve_duplicateSkus_areMerged() throws Exception {
        UUID orderId = UUID.randomUUID();

        reserve(orderId, item("SKU-A", 2), item("SKU-A", 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].qty").value(5));

        assertStock("SKU-A", 5, 5);
    }

    @Test
    void reserve_duplicateSkusExceedingStockOnlyWhenMerged_returns409() throws Exception {
        // 各自 1 件都夠，合併後 2 件就不夠
        reserve(UUID.randomUUID(), item("SKU-C", 1), item("SKU-C", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));

        assertStock("SKU-C", 1, 0);
    }

    // ---------- 失敗：全有全無 ----------

    @Test
    void reserve_oneSkuInsufficient_deductsNothing() throws Exception {
        UUID orderId = UUID.randomUUID();

        // SKU-A 夠、SKU-C 不夠：SKU-A 也不能被扣
        reserve(orderId, item("SKU-A", 3), item("SKU-C", 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail", containsString("SKU-C")))
                .andExpect(jsonPath("$.detail", not(containsString("SKU-A"))));

        assertStock("SKU-A", 10, 0);
        assertStock("SKU-C", 1, 0);
        assertThat(countReservations()).isZero();
    }

    @Test
    void reserve_severalSkusInsufficient_listsAllOfThem() throws Exception {
        reserve(UUID.randomUUID(), item("SKU-B", 6), item("SKU-A", 1), item("SKU-C", 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("SKU-B, SKU-C")));

        assertStock("SKU-A", 10, 0);
    }

    @Test
    void reserve_reservedQuantityIsNotSellable() throws Exception {
        // SKU-B available 5、reserved 1：只能再賣 5 件
        reserve(UUID.randomUUID(), item("SKU-B", 6))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void reserve_unknownSku_returns404AndDeductsNothing() throws Exception {
        reserve(UUID.randomUUID(), item("SKU-A", 1), item("NO-SUCH-1", 1), item("NO-SUCH-2", 1), item("SKU-C", 5))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"))
                .andExpect(jsonPath("$.detail", containsString("NO-SUCH-1, NO-SUCH-2")));

        assertStock("SKU-A", 10, 0);
        assertThat(countReservations()).isZero();
    }

    @Test
    void reserve_failedReservation_canBeRetriedWithSameOrderId() throws Exception {
        // 失敗時不留下紀錄，補貨後用同一個 orderId 重試可以成功
        UUID orderId = UUID.randomUUID();
        reserve(orderId, item("SKU-C", 2)).andExpect(status().isConflict());
        jdbcTemplate.update("UPDATE inventory_service.stock SET available = 2 WHERE sku = 'SKU-C'");

        reserve(orderId, item("SKU-C", 2)).andExpect(status().isCreated());
        assertStock("SKU-C", 0, 2);
    }

    // ---------- 冪等 ----------

    @Test
    void reserve_sameOrderIdAndItems_returns200WithoutDeductingAgain() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, item("SKU-A", 3), item("SKU-B", 1)).andExpect(status().isCreated());

        // 順序不同、拆成兩筆，合併後一樣就算相同
        reserve(orderId, item("SKU-B", 1), item("SKU-A", 1), item("SKU-A", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.items.length()").value(2));

        assertStock("SKU-A", 7, 3);
        assertStock("SKU-B", 4, 2);
    }

    @Test
    void reserve_sameOrderIdAfterConfirmed_returns200WithConfirmedStatus() throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, item("SKU-A", 1)).andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE inventory_service.reservation SET status = 'CONFIRMED' WHERE order_id = ?",
                orderId);

        reserve(orderId, item("SKU-A", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertStock("SKU-A", 9, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "qty", "sku", "extra" })
    void reserve_sameOrderIdDifferentItems_returns409(String difference) throws Exception {
        UUID orderId = UUID.randomUUID();
        reserve(orderId, item("SKU-A", 3)).andExpect(status().isCreated());

        String[] items = switch (difference) {
            case "qty" -> new String[] { item("SKU-A", 4) };
            case "sku" -> new String[] { item("SKU-B", 3) };
            default -> new String[] { item("SKU-A", 3), item("SKU-B", 1) };
        };
        reserve(orderId, items)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_MISMATCH"))
                .andExpect(jsonPath("$.detail", containsString(orderId.toString())));

        assertStock("SKU-A", 7, 3);
        assertStock("SKU-B", 5, 1);
    }

    @Test
    void reserve_releasedOrderId_returns409AndDeductsNothing() throws Exception {
        // 釋放過的 orderId（包含預扣請求還沒到就先釋放的情況）：延遲送達的預扣要被擋下
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO inventory_service.reservation (order_id, status, created_by, updated_by)
                VALUES (?, 'RELEASED', ?, ?)
                """, orderId, SYSTEM, SYSTEM);

        reserve(orderId, item("SKU-A", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_ALREADY_RELEASED"));

        assertStock("SKU-A", 10, 0);
    }

    // ---------- 參數驗證 ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"items\": [{\"sku\": \"SKU-A\", \"qty\": 1}]}",
            "{\"orderId\": \"not-a-uuid\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": 1}]}",
            "{\"orderId\": \"%s\"}",
            "{\"orderId\": \"%s\", \"items\": []}",
            "{\"orderId\": \"%s\", \"items\": [null]}",
            "{\"orderId\": \"%s\", \"items\": [{\"qty\": 1}]}",
            "{\"orderId\": \"%s\", \"items\": [{\"sku\": \" \", \"qty\": 1}]}",
            "{\"orderId\": \"%s\", \"items\": [{\"sku\": \"SKU-A\"}]}",
            "{\"orderId\": \"%s\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": 0}]}",
            "{\"orderId\": \"%s\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": -1}]}",
            "{\"orderId\": \"%s\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": 1.5}]}",
            "{\"orderId\": \"%s\", \"items\": [{\"sku\": \"SKU-A\", \"qty\": 1000001}]}" })
    void reserve_invalidBody_returns400(String template) throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(template.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        assertStock("SKU-A", 10, 0);
        assertThat(countReservations()).isZero();
    }

    @Test
    void reserve_skuLongerThan64_returns400() throws Exception {
        reserve(UUID.randomUUID(), item("A".repeat(65), 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void reserve_moreThan100Items_returns400() throws Exception {
        String[] items = IntStream.range(0, 101).mapToObj(i -> item("SKU-A", 1)).toArray(String[]::new);

        reserve(UUID.randomUUID(), items)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 併發 ----------

    @Test
    void reserve_concurrentOrdersForLastUnit_onlyOneSucceeds() throws Exception {
        // SKU-C 只剩 1 件，10 張不同的訂單同時搶
        List<Integer> statuses = runConcurrently(10, i -> reserve(UUID.randomUUID(), item("SKU-C", 1)));

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
        assertStock("SKU-C", 0, 1);
        assertThat(countReservations()).isEqualTo(1);
    }

    @Test
    void reserve_concurrentOrdersOnSameSkus_neverOversellAndNoDeadlock() throws Exception {
        // 一半的請求 A、B 順序相反：沒有排序的話容易死結
        List<Integer> statuses = runConcurrently(20, i -> i % 2 == 0
                ? reserve(UUID.randomUUID(), item("SKU-A", 1), item("SKU-B", 1))
                : reserve(UUID.randomUUID(), item("SKU-B", 1), item("SKU-A", 1)));

        // SKU-B 只有 5 件可賣 → 最多 5 張成功
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(5);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(15);
        assertStock("SKU-A", 5, 5);
        assertStock("SKU-B", 0, 6);
    }

    @Test
    void reserve_concurrentRetriesOfSameOrder_deductOnlyOnce() throws Exception {
        UUID orderId = UUID.randomUUID();

        List<Integer> statuses = runConcurrently(5, i -> reserve(orderId, item("SKU-A", 2)));

        // 一個 201；其他看到已存在的紀錄回 200，或同時寫入被主鍵擋下回 409 DATA_CONFLICT
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 200 || s == 409);
        assertStock("SKU-A", 8, 2);
        assertThat(countReservations()).isEqualTo(1);
    }

    // ---------- helpers ----------

    private interface Request {
        ResultActions send(int index) throws Exception;
    }

    // 所有執行緒準備好後同時送出，回傳每個請求的 HTTP 狀態
    private List<Integer> runConcurrently(int count, Request request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<Integer> task = () -> {
                    start.await();
                    return request.send(index).andReturn().getResponse().getStatus();
                };
                futures.add(executor.submit(task));
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

    private ResultActions reserve(UUID orderId, String... items) throws Exception {
        String body = """
                {"orderId": "%s", "items": [%s]}
                """.formatted(orderId, String.join(", ", items));
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String item(String sku, int qty) {
        return "{\"sku\": \"%s\", \"qty\": %d}".formatted(sku, qty);
    }

    private void insertStock(String sku, int available, int reserved) {
        jdbcTemplate.update("""
                INSERT INTO inventory_service.stock (sku, available, reserved, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                """, sku, available, reserved, SYSTEM, SYSTEM);
    }

    private Map<String, Object> findStock(String sku) {
        return jdbcTemplate.queryForMap("SELECT * FROM inventory_service.stock WHERE sku = ?", sku);
    }

    private void assertStock(String sku, int available, int reserved) {
        Map<String, Object> row = findStock(sku);
        assertThat(row.get("available")).as(sku + " available").isEqualTo(available);
        assertThat(row.get("reserved")).as(sku + " reserved").isEqualTo(reserved);
    }

    private String reservationStatus(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM inventory_service.reservation WHERE order_id = ?",
                String.class, orderId);
    }

    private Map<String, Integer> reservationItems(UUID orderId) {
        return jdbcTemplate.queryForList(
                "SELECT sku, qty FROM inventory_service.reservation_item WHERE order_id = ?", orderId).stream()
                .collect(Collectors.toMap(row -> (String) row.get("sku"), row -> (Integer) row.get("qty")));
    }

    private int countReservations() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_service.reservation", Integer.class);
    }
}

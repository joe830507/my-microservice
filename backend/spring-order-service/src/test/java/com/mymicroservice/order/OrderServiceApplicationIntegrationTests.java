package com.mymicroservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// 專案骨架：context 啟動（ddl-auto=validate 會比對實體與 Flyway 建的資料表）、health、資料表的約束
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderServiceApplicationIntegrationTests {
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
    }

    @Test
    void health_isUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flyway_createsAllTables() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'order_service' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly("order_item", "orders", "outbox_event", "processed_event", "shedlock");
    }

    @Test
    void validOrder_isAccepted() {
        UUID orderId = insertOrder("PENDING_PAYMENT", "RESERVED", "100.00");
        insertItem(orderId, 1, "50.00", "100.00");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.order_item", Integer.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING", "paid", "REFUNDED" })
    void orders_unknownStatus_isRejectedByDb(String status) {
        assertThatThrownBy(() -> insertOrder(status, "RESERVED", "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orders_unknownReservationStatus_isRejectedByDb() {
        assertThatThrownBy(() -> insertOrder("PENDING_PAYMENT", "PENDING", "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orders_negativeTotal_isRejectedByDb() {
        assertThatThrownBy(() -> insertOrder("PENDING_PAYMENT", "RESERVED", "-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderItem_nonPositiveQtyOrNegativePrice_isRejectedByDb() {
        UUID orderId = insertOrder("PENDING_PAYMENT", "RESERVED", "0");

        assertThatThrownBy(() -> insertItem(orderId, 0, "10.00", "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertItem(orderId, 1, "-1.00", "0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orderItem_unknownOrder_isRejectedByDb() {
        assertThatThrownBy(() -> insertItem(UUID.randomUUID(), 1, "10.00", "10.00"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertOrder(String status, String reservationStatus, String totalAmount) {
        UUID orderId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO order_service.orders
                    (id, user_id, status, reservation_status, reservation_synced, total_amount, created_by, updated_by)
                VALUES (?, ?, ?, ?, true, ?::numeric, ?, ?)
                """, orderId, UUID.randomUUID(), status, reservationStatus, totalAmount, SYSTEM, SYSTEM);
        return orderId;
    }

    private void insertItem(UUID orderId, int qty, String unitPrice, String subtotal) {
        jdbcTemplate.update("""
                INSERT INTO order_service.order_item
                    (order_id, product_id, sku, product_name, unit_price, qty, subtotal)
                VALUES (?, ?, 'SKU-001', 'Shoe', ?::numeric, ?, ?::numeric)
                """, orderId, UUID.randomUUID(), unitPrice, qty, subtotal);
    }
}

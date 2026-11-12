package com.mymicroservice.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
class InventoryServiceApplicationIntegrationTests {
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE inventory_service.reservation, inventory_service.stock CASCADE");
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
                WHERE table_schema = 'inventory_service' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly("outbox_event", "processed_event", "reservation", "reservation_item", "shedlock", "stock");
    }

    @Test
    void stock_negativeQuantities_areRejectedByDb() {
        // 程式出錯時的最後防線：available、reserved 不能變成負數
        insertStock("SKU-001", 1, 0);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE inventory_service.stock SET available = available - 2 WHERE sku = 'SKU-001'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE inventory_service.stock SET reserved = reserved - 1 WHERE sku = 'SKU-001'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservationItem_unknownSku_isRejectedByDb() {
        UUID orderId = UUID.randomUUID();
        insertReservation(orderId, "RESERVED");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO inventory_service.reservation_item (order_id, sku, qty) VALUES (?, 'NO-SUCH-SKU', 1)",
                orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservationItem_nonPositiveQty_isRejectedByDb() {
        UUID orderId = UUID.randomUUID();
        insertStock("SKU-001", 1, 0);
        insertReservation(orderId, "RESERVED");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO inventory_service.reservation_item (order_id, sku, qty) VALUES (?, 'SKU-001', 0)",
                orderId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservation_unknownStatus_isRejectedByDb() {
        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertStock(String sku, int available, int reserved) {
        jdbcTemplate.update("""
                INSERT INTO inventory_service.stock (sku, available, reserved, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                """, sku, available, reserved, SYSTEM, SYSTEM);
    }

    private void insertReservation(UUID orderId, String status) {
        jdbcTemplate.update("""
                INSERT INTO inventory_service.reservation (order_id, status, created_by, updated_by)
                VALUES (?, ?, ?, ?)
                """, orderId, status, SYSTEM, SYSTEM);
    }
}

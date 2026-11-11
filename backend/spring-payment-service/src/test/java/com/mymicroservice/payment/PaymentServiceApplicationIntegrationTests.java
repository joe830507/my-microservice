package com.mymicroservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

// 專案骨架：context 啟動（ddl-auto=validate 比對實體與資料表）、health、資料表的約束
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentServiceApplicationIntegrationTests {
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("TRUNCATE payment_service.payment");
    }

    @Test
    void health_isUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void flyway_createsPaymentTable() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'payment_service' AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder("payment", "shedlock", "outbox_event");
    }

    @Test
    void oneOrder_allowsOnlyOneActivePayment() {
        UUID orderId = UUID.randomUUID();
        insert(orderId, "PENDING", null);

        // 進行中的付款還在，不能再建一筆
        assertThatThrownBy(() -> insert(orderId, "PENDING", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(orderId, "SUCCEEDED", "DONE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneOrder_allowsRetryAfterFailedPayments() {
        UUID orderId = UUID.randomUUID();
        insert(orderId, "FAILED", null);
        insert(orderId, "FAILED", null);

        assertThatCode(() -> insert(orderId, "PENDING", null)).doesNotThrowAnyException();
    }

    @Test
    void succeededPayment_mustHaveNotificationStatus() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "SUCCEEDED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "PENDING", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownStatusOrNegativeAmount_isRejectedByDb() {
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "REFUNDED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO payment_service.payment (id, order_id, user_id, amount, status, created_by, updated_by)
                VALUES (?, ?, ?, -1, 'PENDING', ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SYSTEM, SYSTEM))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insert(UUID orderId, String status, String orderNotification) {
        jdbcTemplate.update("""
                INSERT INTO payment_service.payment
                    (id, order_id, user_id, amount, status, order_notification, created_by, updated_by)
                VALUES (?, ?, ?, 100.00, ?, ?, ?, ?)
                """, UUID.randomUUID(), orderId, UUID.randomUUID(), status, orderNotification, SYSTEM, SYSTEM);
    }
}

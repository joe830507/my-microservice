package com.mymicroservice.payment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

// 測試資料：直接用 SQL 建立付款，查詢、回呼、排程的測試不需要經過建立付款（也不需要模擬 order-service）
public final class PaymentTestData {
    public static final UUID SYSTEM = new UUID(0L, 0L);

    private PaymentTestData() {
    }

    // 金額固定 399.98；SUCCEEDED 時 orderNotification 不能是 null（DB 約束）
    public static UUID insertPayment(JdbcTemplate jdbcTemplate, UUID orderId, UUID userId, String status,
            String orderNotification, Instant updatedAt) {
        UUID paymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_service.payment
                    (id, order_id, user_id, amount, status, order_notification, completed_at,
                     created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, 399.98, ?, ?, ?, ?, ?, ?, ?)
                """, paymentId, orderId, userId, status, orderNotification,
                "PENDING".equals(status) ? null : Timestamp.from(updatedAt),
                Timestamp.from(updatedAt), Timestamp.from(updatedAt), userId, userId);
        return paymentId;
    }

    public static UUID insertPendingPayment(JdbcTemplate jdbcTemplate, UUID orderId, UUID userId) {
        return insertPayment(jdbcTemplate, orderId, userId, "PENDING", null, Instant.now());
    }
}

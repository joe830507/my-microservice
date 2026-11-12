package com.mymicroservice.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

// 測試資料：直接用 SQL 建立訂單，查詢、列表、取消等測試不需要經過建單（也不需要模擬 product、inventory）
public final class OrderTestData {
    public static final UUID SYSTEM = new UUID(0L, 0L);

    private OrderTestData() {
    }

    // 一張訂單、一個品項：單價 100.00 × qty
    public static UUID insertOrder(JdbcTemplate jdbcTemplate, UUID userId, String status, int qty,
            Instant createdAt) {
        UUID orderId = UUID.randomUUID();
        String total = String.valueOf(100 * qty) + ".00";
        String reservationStatus = switch (status) {
            case "CANCELLED" -> "RELEASED";
            case "PENDING_PAYMENT" -> "RESERVED";
            default -> "CONFIRMED";
        };
        jdbcTemplate.update("""
                INSERT INTO order_service.orders
                    (id, user_id, status, reservation_status, total_amount,
                     created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?::numeric, ?, ?, ?, ?)
                """, orderId, userId, status, reservationStatus, total, Timestamp.from(createdAt),
                Timestamp.from(createdAt), userId, userId);
        jdbcTemplate.update("""
                INSERT INTO order_service.order_item
                    (order_id, product_id, sku, product_name, unit_price, qty, subtotal)
                VALUES (?, ?, 'SHOE-001', 'Running Shoe', 100.00, ?, ?::numeric)
                """, orderId, UUID.randomUUID(), qty, total);
        return orderId;
    }

    public static UUID insertOrder(JdbcTemplate jdbcTemplate, UUID userId, String status) {
        return insertOrder(jdbcTemplate, userId, status, 1, Instant.now());
    }
}

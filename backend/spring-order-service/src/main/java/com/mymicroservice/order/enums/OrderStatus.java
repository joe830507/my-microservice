package com.mymicroservice.order.enums;

// PENDING_PAYMENT → PAID → SHIPPED → COMPLETED
// PENDING_PAYMENT → CANCELLED（使用者取消或付款逾時）
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    COMPLETED,
    CANCELLED
}

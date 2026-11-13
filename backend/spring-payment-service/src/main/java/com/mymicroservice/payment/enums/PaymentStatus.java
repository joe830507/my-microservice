package com.mymicroservice.payment.enums;

// PENDING → SUCCEEDED／FAILED（回呼），或 CANCELLED（訂單取消）；三者都是終態。
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
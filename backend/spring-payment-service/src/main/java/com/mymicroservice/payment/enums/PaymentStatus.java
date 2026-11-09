package com.mymicroservice.payment.enums;

// PENDING → SUCCEEDED 或 FAILED（由金流回呼決定）；SUCCEEDED、FAILED 都是終點
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED
}

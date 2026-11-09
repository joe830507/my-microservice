package com.mymicroservice.payment.exception;

import org.springframework.http.HttpStatus;

import com.mymicroservice.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// payment-service 的業務錯誤碼；共用錯誤碼（VALIDATION_FAILED 等）在 common 的 CommonErrorCode
@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    // 付款不存在，或不是自己的付款
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "payment not found: %s."),

    // 訂單不存在，或不是自己的訂單（不透露別人的訂單存在）
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "order not found: %s."),
    // 訂單不是待付款（例如已取消）
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "order %s cannot be paid in status %s."),
    // 這張訂單已經有成功的付款
    ORDER_ALREADY_PAID(HttpStatus.CONFLICT, "order %s has already been paid."),

    // order-service 連不上、逾時或 5xx；用戶端可以稍後重試
    ORDER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "order service is temporarily unavailable.");

    private final HttpStatus status;
    private final String message;
}

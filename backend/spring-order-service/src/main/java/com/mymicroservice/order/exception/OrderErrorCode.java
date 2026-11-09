package com.mymicroservice.order.exception;

import org.springframework.http.HttpStatus;

import com.mymicroservice.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// order-service 的業務錯誤碼；共用錯誤碼（VALIDATION_FAILED 等）在 common 的 CommonErrorCode
// 名稱即回應中的 code；message 可含 %s 佔位符，由 AppException 建構時帶入參數
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    // 不存在，或不是自己的訂單（不透露別人的訂單 id 存在）
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "order not found: %s."),
    // 只有 PENDING_PAYMENT 可以取消；已付款的要走退款流程（雛型階段不做）
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "order %s cannot be cancelled in status %s."),
    // 付款成功通知送到時訂單已經取消（例如付款逾時被自動取消）；payment-service 要退款
    ORDER_ALREADY_CANCELLED(HttpStatus.CONFLICT, "order %s has already been cancelled."),
    // 只有 PAID 可以出貨
    ORDER_NOT_SHIPPABLE(HttpStatus.CONFLICT, "order %s cannot be shipped in status %s."),

    // 建立訂單：列出所有有問題的商品 id 或 SKU，以逗號分隔
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "product not found: %s."),
    // 商品存在但不是上架狀態（DRAFT、INACTIVE、ARCHIVED）
    PRODUCT_NOT_AVAILABLE(HttpStatus.CONFLICT, "product is not available for purchase: %s."),
    // 庫存不足，或 inventory 沒有這個 SKU 的庫存紀錄
    OUT_OF_STOCK(HttpStatus.CONFLICT, "out of stock: %s."),

    // 下游服務連不上、逾時或 5xx；用戶端可以稍後重試
    PRODUCT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "product service is temporarily unavailable."),
    INVENTORY_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "inventory service is temporarily unavailable.");

    private final HttpStatus status;
    private final String message;
}

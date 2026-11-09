package com.mymicroservice.inventory.exception;

import org.springframework.http.HttpStatus;

import com.mymicroservice.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// inventory-service 的業務錯誤碼；共用錯誤碼（VALIDATION_FAILED 等）在 common 的 CommonErrorCode
// 名稱即回應中的 code；message 可含 %s 佔位符，由 AppException 建構時帶入參數
@Getter
@RequiredArgsConstructor
public enum InventoryErrorCode implements ErrorCode {
    // Stock
    // 預扣時列出所有不存在的 SKU，以逗號分隔
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "stock not found: %s."),

    // Reservation
    // 列出所有庫存不足的 SKU，以逗號分隔；整筆預扣都沒有生效
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "insufficient stock: %s."),
    // 同一個 orderId 已經預扣過，但這次送來的品項或數量不同
    RESERVATION_MISMATCH(HttpStatus.CONFLICT, "reservation for order %s already exists with different items."),
    // 已經釋放過（取消或逾時），不能再預扣或確認；延遲送達的預扣請求會收到這個錯誤
    RESERVATION_ALREADY_RELEASED(HttpStatus.CONFLICT, "reservation for order %s has already been released."),
    // 已付款的訂單不能釋放預扣，要走退貨流程（雛型階段不做）
    RESERVATION_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "reservation for order %s has already been confirmed."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "reservation for order %s not found.");

    private final HttpStatus status;
    private final String message;
}

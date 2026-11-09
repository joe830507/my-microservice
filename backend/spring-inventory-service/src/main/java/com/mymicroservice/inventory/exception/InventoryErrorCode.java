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
    STOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "stock not found: %s.");

    private final HttpStatus status;
    private final String message;
}

package com.mymicroservice.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 所有服務共用的錯誤碼；業務錯誤碼放在各服務自己的 enum
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "request validation failed."),
    // 需要登入的 API 沒有收到合法的 X-User-Id；經過 Gateway 時不應發生
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "authentication is required."),
    // 已登入但角色不足，例如非 ADMIN 呼叫後台 API
    FORBIDDEN(HttpStatus.FORBIDDEN, "access is denied."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "data has been modified by others, please reload."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "data conflicts with existing data."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "server error, please contact administrator.");

    private final HttpStatus status;
    private final String message;
}

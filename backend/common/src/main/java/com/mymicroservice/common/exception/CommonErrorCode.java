package com.mymicroservice.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 所有服務共用的錯誤碼；業務錯誤碼放在各服務自己的 enum
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "request validation failed."),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "data has been modified by others, please reload."),
    DATA_CONFLICT(HttpStatus.CONFLICT, "data conflicts with existing data."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "server error, please contact administrator.");

    private final HttpStatus status;
    private final String message;
}

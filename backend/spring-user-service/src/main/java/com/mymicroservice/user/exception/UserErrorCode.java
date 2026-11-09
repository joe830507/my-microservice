package com.mymicroservice.user.exception;

import org.springframework.http.HttpStatus;

import com.mymicroservice.common.exception.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// user-service 的業務錯誤碼；共用錯誤碼（VALIDATION_FAILED 等）在 common 的 CommonErrorCode
// 名稱即回應中的 code，前端可依 code 判斷錯誤類型，不需比對訊息文字
// message 可含 %s 佔位符，由 AppException 建構時帶入參數
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    // Registration
    // 併發註冊繞過檢查時，由 uq_users_email 擋下並回 CommonErrorCode.DATA_CONFLICT
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "email is already registered."),

    // Authentication
    // 帳號不存在與密碼錯誤一律回此錯誤，避免被用來探測哪些 email 已註冊
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "email or password is incorrect."),
    // 必須在密碼驗證通過後才檢查，否則同樣會洩漏帳號是否存在
    USER_DISABLED(HttpStatus.FORBIDDEN, "user is disabled."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "user not found.");

    private final HttpStatus status;
    private final String message;
}

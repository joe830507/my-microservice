package com.mymicroservice.product.exception;

import lombok.Getter;

// 所有業務錯誤統一丟出此例外，HTTP 狀態與訊息由 ErrorCode 決定
@Getter
public class AppException extends RuntimeException {
    private final ErrorCode errorCode;

    // args 對應 ErrorCode.message 中的 %s 佔位符
    public AppException(ErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }
}

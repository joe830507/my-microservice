package com.mymicroservice.common.exception;

import org.springframework.http.HttpStatus;

// 各服務以 enum 實作此介面（例如 ProductErrorCode）；name() 即回應中的 code，前端依 code 判斷錯誤類型
// 方法名稱對齊 Lombok @Getter 產生的 getter，enum 加上 implements 即可，不需額外實作
public interface ErrorCode {

    HttpStatus getStatus();

    // 可含 %s 佔位符，由 AppException 建構時帶入參數
    String getMessage();

    // enum 內建
    String name();
}

package com.mymicroservice.gateway.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// Gateway 對每個下游服務的熔斷設定（每個路由一個熔斷器，設定相同）
// 只有「連不上、逾時」這類例外算失敗；下游回的 HTTP 狀態碼（含 5xx）原樣轉給用戶端，不算失敗：
// 那些是下游自己的業務錯誤（例如 PRODUCT_SERVICE_UNAVAILABLE），前端要依 code 顯示訊息，不能被通用的 503 蓋掉
@Validated
@ConfigurationProperties(prefix = "gateway.resilience")
public record GatewayResilienceProperties(
        @DefaultValue("20") @Min(2) int slidingWindowSize,

        @DefaultValue("10") @Min(1) int minimumNumberOfCalls,

        @DefaultValue("50") @Min(1) @Max(100) int failureRateThreshold,

        @DefaultValue("10s") @NotNull Duration waitDurationInOpenState,

        @DefaultValue("3") @Min(1) int permittedCallsInHalfOpenState,

        // 單一請求的上限；要比 httpclient.response-timeout 長，讓逾時由 HTTP 層先觸發（回 504 並保留原因）
        @DefaultValue("15s") @NotNull Duration timeLimit) {
}

package com.mymicroservice.common.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.bind.DefaultValue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// 呼叫一個下游服務的熔斷與重試設定；各服務把它放在自己的 @ConfigurationProperties 裡（例如 order.services.resilience）
// 預設值適合「下游平常很快、壞掉時要快點放棄」的內部呼叫
public record ResilienceProperties(
        // 熔斷：最近這麼多次呼叫裡失敗的比例超過門檻就打開（打開後直接失敗，不再呼叫下游）
        @DefaultValue("20") @Min(2) int slidingWindowSize,

        // 呼叫次數不到這個數字之前不評估失敗比例（避免剛啟動的前兩次失敗就打開）
        @DefaultValue("10") @Min(1) int minimumNumberOfCalls,

        @DefaultValue("50") @Min(1) @Max(100) int failureRateThreshold,

        // 打開之後多久才放少量請求試探
        @DefaultValue("10s") Duration waitDurationInOpenState,

        // 試探期間放行的請求數；全部成功才關閉
        @DefaultValue("3") @Min(1) int permittedCallsInHalfOpenState,

        // 只用在冪等的呼叫：總嘗試次數（含第一次）；1 代表不重試
        @DefaultValue("3") @Min(1) int retryMaxAttempts,

        // 第一次重試前等多久，之後每次加倍
        @DefaultValue("100ms") Duration retryInitialBackoff) {
}

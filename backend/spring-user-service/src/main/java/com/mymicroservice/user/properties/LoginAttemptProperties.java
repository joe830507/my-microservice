package com.mymicroservice.user.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 app.login-attempt，供 LoginAttemptService 使用
@Validated
@ConfigurationProperties(prefix = "app.login-attempt")
public record LoginAttemptProperties(
        // window 內累積失敗達到這個次數就鎖住
        @Min(1) int maxFailures,

        // 從第一次失敗開始計算的固定時間窗；鎖住會持續到 window 結束
        @NotNull Duration window) {

}

package com.mymicroservice.user.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 app.login-attempt，供 LoginAttemptService 使用
@Validated
@ConfigurationProperties(prefix = "app.login-attempt")
public record LoginAttemptProperties(
        // 同一個（email + 來源 IP）在 window 內累積失敗達到這個次數就鎖住那一組。
        // 依來源 IP 分開計算：單一攻擊者來源只鎖得到自己，鎖不到從別的地方登入的本人（SEC-05）
        @Min(1) int maxFailures,

        // 同一個 email 在 window 內不分來源的失敗總次數上限，擋住分散在很多 IP 的暴力猜密碼；
        // 設得比 maxFailures 高：要湊到這個數字需要很多個來源，而每個來源在 Gateway 還有登入限流
        @Min(1) @DefaultValue("30") int maxFailuresPerEmail,

        // 從第一次失敗開始計算的固定時間窗；鎖住會持續到 window 結束
        @NotNull Duration window) {

}

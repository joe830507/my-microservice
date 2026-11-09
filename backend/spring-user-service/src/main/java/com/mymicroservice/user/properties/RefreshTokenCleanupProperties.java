package com.mymicroservice.user.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 app.refresh-token-cleanup，供 RefreshTokenCleanupJob 使用
@Validated
@ConfigurationProperties(prefix = "app.refresh-token-cleanup")
public record RefreshTokenCleanupProperties(
        // Spring 的 cron 格式（秒 分 時 日 月 星期），時區固定 UTC；設為 "-" 停用排程
        @NotBlank String cron,

        // 過期超過這段時間才刪除，留一段時間方便查問題
        @NotNull Duration retention) {

}

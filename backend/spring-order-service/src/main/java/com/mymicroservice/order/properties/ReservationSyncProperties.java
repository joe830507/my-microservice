package com.mymicroservice.order.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 app.reservation-sync，供 ReservationSyncJob 使用
@Validated
@ConfigurationProperties(prefix = "app.reservation-sync")
public record ReservationSyncProperties(
        // Spring 的 cron 格式（秒 分 時 日 月 星期），時區固定 UTC；設為 "-" 停用排程
        @NotBlank String cron,

        // 最後更新超過這段時間才重試，避免和正在呼叫 inventory 的請求重複
        @NotNull Duration minAge,

        @NotNull @Min(1) @Max(1000) Integer batchSize) {
}

package com.mymicroservice.inventory.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 app.reservation-expiry，供 ReservationExpiryJob 使用
@Validated
@ConfigurationProperties(prefix = "app.reservation-expiry")
public record ReservationExpiryProperties(
        // Spring 的 cron 格式（秒 分 時 日 月 星期），時區固定 UTC；設為 "-" 停用排程
        @NotBlank String cron,

        // 預扣超過這段時間仍是 RESERVED 就釋放；必須比 order-service 的付款期限長，
        // 否則使用者在期限內付款，確認時預扣卻已經被釋放
        @NotNull Duration ttl,

        // 每批最多處理幾筆，避免一次載入太多
        @NotNull @Min(1) @Max(1000) Integer batchSize) {
}

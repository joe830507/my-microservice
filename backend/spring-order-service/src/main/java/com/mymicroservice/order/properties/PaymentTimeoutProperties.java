package com.mymicroservice.order.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 app.payment-timeout，供 PaymentTimeoutJob 使用
@Validated
@ConfigurationProperties(prefix = "app.payment-timeout")
public record PaymentTimeoutProperties(
        // Spring 的 cron 格式（秒 分 時 日 月 星期），時區固定 UTC；設為 "-" 停用排程
        @NotBlank String cron,

        // 建立超過這段時間仍未付款就自動取消；必須比 inventory 的預扣逾時（1 小時）短
        @NotNull Duration timeout,

        @NotNull @Min(1) @Max(1000) Integer batchSize) {
}

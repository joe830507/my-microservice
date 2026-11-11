package com.mymicroservice.order.properties;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import com.mymicroservice.common.resilience.ResilienceProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

// order-service 呼叫的下游服務；本機是 localhost，K8s 用環境變數改成 Service 名稱
@Validated
@ConfigurationProperties(prefix = "order.services")
public record DownstreamServicesProperties(
        @NotNull URI productUri,

        @NotNull URI inventoryUri,

        // 連線建立的時限
        @NotNull Duration connectTimeout,

        // 連上之後等回應的時限；超過就當成下游無法使用
        @NotNull Duration readTimeout,

        // 熔斷與重試（product、inventory 各自一個熔斷器，設定相同）
        @Valid @DefaultValue ResilienceProperties resilience) {
}

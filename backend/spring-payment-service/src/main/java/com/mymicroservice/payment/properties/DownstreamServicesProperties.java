package com.mymicroservice.payment.properties;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

// payment-service 呼叫的下游服務；本機是 localhost，K8s 用環境變數改成 Service 名稱
@Validated
@ConfigurationProperties(prefix = "payment.services")
public record DownstreamServicesProperties(
        @NotNull URI orderUri,

        @NotNull Duration connectTimeout,

        @NotNull Duration readTimeout) {
}

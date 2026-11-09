package com.mymicroservice.gateway.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

// 如何判斷來源 IP（限流的 key）
@Validated
@ConfigurationProperties(prefix = "gateway.client-ip")
public record ClientIpProperties(
        // Gateway 前面有幾層會加 X-Forwarded-For 的代理；0 = 直接用連線 IP
        @Min(0) int trustedProxies) {
}

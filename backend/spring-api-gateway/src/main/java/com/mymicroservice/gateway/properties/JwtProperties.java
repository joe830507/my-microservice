package com.mymicroservice.gateway.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 驗證 user-service 簽發的 access token
@Validated
@ConfigurationProperties(prefix = "gateway.security.jwt")
public record JwtProperties(
        // user-service 的 /api/v1/auth/jwks；依 token header 的 kid 挑公鑰，遇到不認得的 kid 會重新抓
        @NotBlank String jwkSetUri,
        // 必須和 user-service 的 jwt.issuer 一致
        @NotBlank String issuer,
        // 抓 JWKS 的時限：user-service 卡住時，需要登入的請求不會跟著一直等，改回 503
        @NotNull Duration jwksConnectTimeout,
        @NotNull Duration jwksResponseTimeout) {
}

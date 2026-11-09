package com.mymicroservice.user.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 jwt 區塊；加 @Validated，設定漏了啟動時就失敗，不用等到登入才發現
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        // 放的是金鑰路徑而非內容，Spring 會把 file:...、classpath:... 轉成 Resource
        // 私鑰須為 PKCS#8（BEGIN PRIVATE KEY），公鑰須為 X.509（BEGIN PUBLIC KEY）
        @NotNull Resource privateKeyLocation,

        @NotNull Resource publicKeyLocation,

        // JWT header 的 kid，Gateway 用它從 JWKS 找到對應的公鑰；換金鑰時改成新值
        @NotBlank String keyId,

        // JWT 的 iss，Gateway 會檢查
        @NotBlank String issuer,

        // access token 有效時間，yml 可寫 15m 這種格式
        @NotNull Duration accessTokenTtl,

        // refresh token 有效時間，例如 14d；每次 refresh 換新的 token 時重新計算
        @NotNull Duration refreshTokenTtl,

        // 剛輪替掉的 refresh token 在這段時間內再被使用，不視為竊取（多分頁同時 refresh、回應遺失後重送）
        // 設為 0 代表不給寬限，一律當作重複使用
        @NotNull Duration refreshTokenReuseGrace) {

}

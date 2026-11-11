package com.mymicroservice.user.dtos.token;

// 登入與 refresh 的回應 body；欄位命名參考 OAuth 2.0 token response（RFC 6749 5.1）
// refresh token 不在 body：改放在 HttpOnly cookie（SEC-06），前端的 JavaScript 讀不到，XSS 偷不走
public record AccessTokenResDto(
        String accessToken,

        // 固定為 Bearer，前端放在 Authorization: Bearer <accessToken>
        String tokenType,

        // access token 剩餘有效秒數，前端可據此提前換新
        long expiresIn,

        // refresh token（cookie）剩餘有效秒數，到期後需重新登入
        long refreshExpiresIn) {

    public static AccessTokenResDto from(TokenResDto token) {
        return new AccessTokenResDto(token.accessToken(), token.tokenType(), token.expiresIn(),
                token.refreshExpiresIn());
    }
}

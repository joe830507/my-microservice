package com.mymicroservice.user.dtos.token;

// 登入與 refresh 共用的回應；欄位命名參考 OAuth 2.0 token response（RFC 6749 5.1）
public record TokenResDto(
        String accessToken,

        // 固定為 Bearer，前端放在 Authorization: Bearer <accessToken>
        String tokenType,

        // access token 剩餘有效秒數，前端可據此提前換新
        long expiresIn,

        // 換發用的 refresh token；每次 refresh 都會換成新的，舊的立即失效
        String refreshToken,

        // refresh token 剩餘有效秒數，到期後需重新登入
        long refreshExpiresIn) {

    public static TokenResDto bearer(String accessToken, long expiresIn, String refreshToken,
            long refreshExpiresIn) {
        return new TokenResDto(accessToken, "Bearer", expiresIn, refreshToken, refreshExpiresIn);
    }
}

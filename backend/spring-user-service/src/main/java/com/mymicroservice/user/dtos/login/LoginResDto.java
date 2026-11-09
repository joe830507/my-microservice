package com.mymicroservice.user.dtos.login;

// 欄位命名參考 OAuth 2.0 token response（RFC 6749 5.1）；refresh token 在 Step 6 才加
public record LoginResDto(
        String accessToken,

        // 固定為 Bearer，前端放在 Authorization: Bearer <accessToken>
        String tokenType,

        // access token 剩餘有效秒數，前端可據此提前換新
        long expiresIn) {

    public static LoginResDto bearer(String accessToken, long expiresIn) {
        return new LoginResDto(accessToken, "Bearer", expiresIn);
    }
}

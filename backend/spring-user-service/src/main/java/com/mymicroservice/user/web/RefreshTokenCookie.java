package com.mymicroservice.user.web;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

// refresh token 的 cookie（SEC-06）：
//   HttpOnly   JavaScript 讀不到，頁面被 XSS 也偷不走 refresh token
//   SameSite=Strict  跨站的請求不會帶這個 cookie，擋 CSRF
//   Path=/api/v1/auth  只有 refresh、logout 會收到，其他 API 請求不帶它
//   Secure     只在 HTTPS 送；本機純 HTTP 測試用 jwt.refresh-cookie-secure=false 關掉（localhost 不關也可以）
@Component
public class RefreshTokenCookie {
    public static final String NAME = "refresh_token";
    static final String PATH = "/api/v1/auth";

    private final boolean secure;

    public RefreshTokenCookie(@Value("${jwt.refresh-cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie issue(String token, Duration ttl) {
        return base(token).maxAge(ttl).build();
    }

    // 登出時要求瀏覽器刪掉：同名、同 Path、Max-Age=0
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}

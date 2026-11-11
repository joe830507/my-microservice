package com.mymicroservice.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.mymicroservice.user.web.RefreshTokenCookie;

import jakarta.servlet.http.Cookie;

// 測試用：refresh token 在 HttpOnly cookie（SEC-06），不在回應 body
public final class RefreshCookies {
    private RefreshCookies() {
    }

    // 回應的 Set-Cookie 裡的 refresh token
    public static String tokenOf(MvcResult result) {
        String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(RefreshTokenCookie.NAME + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no refresh_token Set-Cookie in the response"));
        String value = header.substring(RefreshTokenCookie.NAME.length() + 1);
        int end = value.indexOf(';');
        return end < 0 ? value : value.substring(0, end);
    }

    // 帶著 refresh token cookie 與 X-Requested-With 的 POST（瀏覽器中 refresh、logout 的樣子）
    public static MockHttpServletRequestBuilder postWithToken(String url, String refreshToken) {
        return post(url)
                .cookie(new Cookie(RefreshTokenCookie.NAME, refreshToken))
                .header("X-Requested-With", "XMLHttpRequest");
    }
}

package com.mymicroservice.user.controller;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.user.dtos.login.LoginReqDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
import com.mymicroservice.user.dtos.token.AccessTokenResDto;
import com.mymicroservice.user.dtos.token.RefreshReqDto;
import com.mymicroservice.user.dtos.token.TokenResDto;
import com.mymicroservice.user.exception.UserErrorCode;
import com.mymicroservice.user.service.AuthService;
import com.mymicroservice.user.web.RefreshTokenCookie;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final String REQUESTED_WITH = "X-Requested-With";

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResDto register(@RequestBody @Valid RegisterReqDto registerReqDto) {
        return authService.register(registerReqDto);
    }

    // 回應 body 只有 access token；refresh token 以 HttpOnly cookie 送出（SEC-06）
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessTokenResDto> login(@RequestBody @Valid LoginReqDto loginReqDto,
            @RequestHeader(value = UserContextHeaders.CLIENT_IP, required = false) String clientIp) {
        return withRefreshCookie(authService.login(loginReqDto, clientIp));
    }

    // 不需要 access token（呼叫時 access token 通常已過期），Gateway 要放行；身分由 refresh token（cookie）本身證明
    // 沒有 body：cookie 由瀏覽器自動帶，不放在 body 或 URL，也就不會出現在存取日誌
    // cookie 是瀏覽器自動帶的，所以多一道檢查：一定要有自訂標頭 X-Requested-With（跨站的表單和圖片請求加不上），
    // 加上 SameSite=Strict，兩層擋 CSRF
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccessTokenResDto> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            @RequestHeader(value = REQUESTED_WITH, required = false) String requestedWith) {
        requireAjax(requestedWith);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AppException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }
        return withRefreshCookie(authService.refresh(new RefreshReqDto(refreshToken)));
    }

    // 和 refresh 一樣不需要 access token，以 refresh token（cookie）本身證明身分
    // token 不存在或已失效也回 204：結果相同，也不洩漏 token 狀態；一律要求瀏覽器刪除 cookie
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            @RequestHeader(value = REQUESTED_WITH, required = false) String requestedWith) {
        requireAjax(requestedWith);
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(new RefreshReqDto(refreshToken));
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString())
                .build();
    }

    private ResponseEntity<AccessTokenResDto> withRefreshCookie(TokenResDto token) {
        ResponseCookie cookie = refreshTokenCookie.issue(token.refreshToken(),
                Duration.ofSeconds(token.refreshExpiresIn()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                // 回應含 token，不讓瀏覽器或中介快取
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(AccessTokenResDto.from(token));
    }

    private static void requireAjax(String requestedWith) {
        if (requestedWith == null || requestedWith.isBlank()) {
            throw new AppException(UserErrorCode.CSRF_CHECK_FAILED);
        }
    }
}

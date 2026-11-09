package com.mymicroservice.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.user.dtos.login.LoginReqDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
import com.mymicroservice.user.dtos.token.RefreshReqDto;
import com.mymicroservice.user.dtos.token.TokenResDto;
import com.mymicroservice.user.service.AuthService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResDto register(@RequestBody @Valid RegisterReqDto registerReqDto) {
        return authService.register(registerReqDto);
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public TokenResDto login(@RequestBody @Valid LoginReqDto loginReqDto) {
        return authService.login(loginReqDto);
    }

    // 不需要 access token（呼叫時 access token 通常已過期），Gateway 要放行；身分由 refresh token 本身證明
    // refresh token 放在 body 而非 URL，避免出現在存取日誌
    @PostMapping(value = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public TokenResDto refresh(@RequestBody @Valid RefreshReqDto refreshReqDto) {
        return authService.refresh(refreshReqDto);
    }

    // 和 refresh 一樣不需要 access token，以 refresh token 本身證明身分
    // token 不存在或已失效也回 204：結果相同，也不洩漏 token 狀態
    @PostMapping(value = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @Valid RefreshReqDto refreshReqDto) {
        authService.logout(refreshReqDto);
    }
}

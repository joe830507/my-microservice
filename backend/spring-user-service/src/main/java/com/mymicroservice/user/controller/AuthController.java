package com.mymicroservice.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.user.dtos.login.LoginReqDto;
import com.mymicroservice.user.dtos.login.LoginResDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
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
    public LoginResDto login(@RequestBody @Valid LoginReqDto loginReqDto) {
        return authService.login(loginReqDto);
    }
}

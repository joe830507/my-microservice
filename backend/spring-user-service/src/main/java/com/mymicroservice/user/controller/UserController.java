package com.mymicroservice.user.controller;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.web.CurrentUserId;
import com.mymicroservice.common.web.RequireRoles;
import com.mymicroservice.user.dtos.me.GetMeResDto;
import com.mymicroservice.user.dtos.users.GetUsersReqDto;
import com.mymicroservice.user.dtos.users.GetUsersResDto;
import com.mymicroservice.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// user-service 不驗 JWT，信任 Gateway 驗簽後帶來的 X-User-Id、X-User-Roles
// 身分與權限的檢查由 common 的 @CurrentUserId、@RequireRoles 處理
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    // 缺少或格式錯誤的 X-User-Id 回 401
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public GetMeResDto getMe(@CurrentUserId UUID userId) {
        return userService.getMe(userId);
    }

    // 後台使用者列表，限 ADMIN
    // 權限在參數驗證之前檢查，非 ADMIN 一律先收到 401／403，不會因為參數錯誤先拿到 400
    // @ParameterObject 讓 springdoc 把 record 欄位展開成個別查詢參數
    @RequireRoles("ADMIN")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public GetUsersResDto getUsers(@ParameterObject @Valid GetUsersReqDto getUsersReqDto) {
        return userService.getUsers(getUsersReqDto);
    }
}

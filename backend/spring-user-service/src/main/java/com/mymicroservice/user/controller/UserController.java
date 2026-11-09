package com.mymicroservice.user.controller;

import java.util.Arrays;
import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.user.dtos.me.GetMeResDto;
import com.mymicroservice.user.dtos.users.GetUsersReqDto;
import com.mymicroservice.user.dtos.users.GetUsersResDto;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// user-service 不驗 JWT，信任 Gateway 驗簽後帶來的 X-User-Id、X-User-Roles
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public GetMeResDto getMe(
            @RequestHeader(name = UserContextHeaders.USER_ID, required = false) String userIdHeader) {
        return userService.getMe(requireUserId(userIdHeader));
    }

    // 後台使用者列表，限 ADMIN
    // @ParameterObject 讓 springdoc 把 record 欄位展開成個別查詢參數
    // 注意：@Valid 在進入方法前就會執行，所以非 ADMIN 送出不合法的參數時會先收到 400 而非 403
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public GetUsersResDto getUsers(
            @RequestHeader(name = UserContextHeaders.USER_ID, required = false) String userIdHeader,
            @RequestHeader(name = UserContextHeaders.USER_ROLES, required = false) String userRolesHeader,
            @ParameterObject @Valid GetUsersReqDto getUsersReqDto) {
        requireUserId(userIdHeader);
        requireRole(userRolesHeader, Role.ADMIN);
        return userService.getUsers(getUsersReqDto);
    }

    // 不直接宣告成 UUID 參數：缺 header 會變成 400、格式錯誤會落到 500，
    // 這裡兩種情況都代表沒有合法身分，統一回 401
    private static UUID requireUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new AppException(CommonErrorCode.UNAUTHENTICATED);
        }
        try {
            return UUID.fromString(userIdHeader.strip());
        } catch (IllegalArgumentException exception) {
            throw new AppException(CommonErrorCode.UNAUTHENTICATED);
        }
    }

    // X-User-Roles 以逗號分隔，例如 CUSTOMER,ADMIN；比對區分大小寫，與 JWT 裡的 roles 一致
    private static void requireRole(String userRolesHeader, Role requiredRole) {
        boolean hasRole = userRolesHeader != null && Arrays.stream(userRolesHeader.split(","))
                .map(String::strip)
                .anyMatch(requiredRole.name()::equals);
        if (!hasRole) {
            throw new AppException(CommonErrorCode.FORBIDDEN);
        }
    }
}

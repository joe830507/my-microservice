package com.mymicroservice.user.controller;

import java.util.UUID;

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
import com.mymicroservice.user.service.UserService;

import lombok.RequiredArgsConstructor;

// user-service 不驗 JWT，信任 Gateway 驗簽後帶來的 X-User-Id
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
}

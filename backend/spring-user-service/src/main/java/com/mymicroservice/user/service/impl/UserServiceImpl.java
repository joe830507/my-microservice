package com.mymicroservice.user.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.user.dtos.me.GetMeResDto;
import com.mymicroservice.user.exception.UserErrorCode;
import com.mymicroservice.user.repositories.UserRepository;
import com.mymicroservice.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    // 停用的帳號仍回傳資料（status 為 DISABLED）：access token 在到期前依然有效，
    // 擋下停用帳號是登入與之後 refresh 的責任，前端可依 status 顯示提示
    @Override
    @Transactional(readOnly = true)
    public GetMeResDto getMe(UUID userId) {
        // token 有效但使用者已不存在（例如被刪除）時回 404
        return userRepository.findWithRolesById(userId)
                .map(GetMeResDto::from)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_FOUND));
    }
}

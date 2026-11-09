package com.mymicroservice.user.service.impl;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;
import com.mymicroservice.user.exception.UserErrorCode;
import com.mymicroservice.user.repositories.UserRepository;
import com.mymicroservice.user.service.AuthService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public RegisterResDto register(RegisterReqDto registerReqDto) {
        // 1. 檢查 email 是否已註冊
        // 併發註冊同一個 email 時兩邊都可能通過此檢查，最後由 uq_users_email 擋下，回 DATA_CONFLICT
        if (userRepository.existsByEmail(registerReqDto.email())) {
            throw new AppException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 2. 組裝使用者：密碼雜湊後才寫入，狀態與角色固定，不由前端決定
        User user = new User();
        user.setEmail(registerReqDto.email());
        user.setPasswordHash(passwordEncoder.encode(registerReqDto.password()));
        user.setDisplayName(registerReqDto.displayName());
        user.setStatus(UserStatus.ACTIVE);
        user.getRoles().add(Role.CUSTOMER);

        User savedUser = userRepository.save(user);
        return RegisterResDto.from(savedUser);
    }
}

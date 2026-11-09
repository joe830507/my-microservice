package com.mymicroservice.user.dtos.register;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;

// 不回傳 passwordHash；即使是雜湊值也不應離開服務
public record RegisterResDto(
        UUID id,

        String email,

        String displayName,

        UserStatus status,

        Set<Role> roles,

        Instant createdAt) {

    public static RegisterResDto from(User user) {
        return new RegisterResDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                // 複製一份，避免把 Hibernate 管理的集合直接交給序列化
                Set.copyOf(user.getRoles()),
                user.getCreatedAt());
    }
}

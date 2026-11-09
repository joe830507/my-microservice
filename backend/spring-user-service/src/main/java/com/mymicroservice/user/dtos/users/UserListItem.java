package com.mymicroservice.user.dtos.users;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;

// 後台列表顯示用的欄位；不回傳 passwordHash
public record UserListItem(
        UUID id,

        String email,

        String displayName,

        UserStatus status,

        Set<Role> roles,

        Instant createdAt) {

    public static UserListItem from(User user) {
        return new UserListItem(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus(),
                // 複製一份，避免把 Hibernate 管理的集合直接交給序列化
                Set.copyOf(user.getRoles()),
                user.getCreatedAt());
    }
}

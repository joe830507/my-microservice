package com.mymicroservice.common.web;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// 解析 Gateway 帶來的身分 header，供 CurrentUserIdArgumentResolver、RequireRolesInterceptor 共用
public final class UserContext {

    private UserContext() {
    }

    // 缺少、空白或不是合法 UUID 都視為沒有身分
    public static Optional<UUID> parseUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(userIdHeader.strip()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    // 以逗號分隔，例如 CUSTOMER,ADMIN；去掉前後空白與空項目，區分大小寫，與 JWT 裡的 roles 一致
    public static Set<String> parseRoles(String userRolesHeader) {
        if (userRolesHeader == null) {
            return Set.of();
        }
        return Arrays.stream(userRolesHeader.split(","))
                .map(String::strip)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}

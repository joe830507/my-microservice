package com.mymicroservice.user.dtos.users;

import java.util.List;

import org.springframework.data.domain.Page;

import com.mymicroservice.user.entities.User;

// 不直接回傳 Page，避免 JSON 結構隨 Spring Data 版本變動；格式與 product-service 的列表一致
public record GetUsersResDto(
        List<UserListItem> items,

        // 從 0 開始
        int page,

        int size,

        long totalElements,

        int totalPages) {

    public static GetUsersResDto from(Page<User> userPage) {
        return new GetUsersResDto(
                userPage.getContent().stream().map(UserListItem::from).toList(),
                userPage.getNumber(),
                userPage.getSize(),
                userPage.getTotalElements(),
                userPage.getTotalPages());
    }
}

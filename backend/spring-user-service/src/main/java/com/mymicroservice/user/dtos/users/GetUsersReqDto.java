package com.mymicroservice.user.dtos.users;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// 以 query string 傳入，所有篩選條件皆為選填，未傳入代表不篩選
public record GetUsersReqDto(
        // 不分大小寫，模糊比對 email 或 displayName
        @Size(max = 254) String keyword,

        UserStatus status,

        // 只列出擁有此角色的使用者
        Role role,

        // 從 0 開始
        @PositiveOrZero Integer page,

        @Min(1) @Max(100) Integer size,

        // 限定可排序欄位，避免依任意欄位排序
        @Pattern(regexp = "^(createdAt|email|displayName)$") String sortBy,

        @Pattern(regexp = "^(asc|desc)$") String sortDirection) {

    // 未傳入的分頁、排序參數補上預設值：第一頁、每頁 20 筆、最新註冊的排前面
    public GetUsersReqDto {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 20;
        }
        if (sortBy == null) {
            sortBy = "createdAt";
        }
        if (sortDirection == null) {
            sortDirection = "desc";
        }
    }

    // 再以 id 排序當作 tie-breaker：主要排序值相同時順序固定，翻頁才不會重複或漏掉資料
    // id 和主要欄位用同一個方向，才能完整對上 (欄位, id) 索引（DESC 時反向掃描）；方向混用就只能靠額外排序
    public Pageable toPageable() {
        Sort.Direction direction = Sort.Direction.fromString(sortDirection);
        Sort sort = Sort.by(direction, sortBy).and(Sort.by(direction, "id"));
        return PageRequest.of(page, size, sort);
    }
}

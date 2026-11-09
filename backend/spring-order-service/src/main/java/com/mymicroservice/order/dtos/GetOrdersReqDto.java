package com.mymicroservice.order.dtos;

import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.mymicroservice.order.enums.OrderStatus;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

// 以 query string 傳入，篩選條件皆為選填
public record GetOrdersReqDto(
        OrderStatus status,

        // 只有 ADMIN 有效；一般使用者一律只查自己的，傳了也會被忽略
        UUID userId,

        // 從 0 開始
        @PositiveOrZero Integer page,

        @Min(1) @Max(100) Integer size) {

    public GetOrdersReqDto {
        if (page == null) {
            page = 0;
        }
        if (size == null) {
            size = 20;
        }
    }

    // 固定最新的在前，id 當 tie-breaker；和 idx_orders_user_created 的順序一致
    public Pageable toPageable() {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
}

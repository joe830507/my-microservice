package com.mymicroservice.order.dtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

import com.mymicroservice.order.entities.Order;
import com.mymicroservice.order.enums.OrderStatus;

// 列表只放摘要，不載入明細（避免每筆訂單多一次查詢）；明細用 GET /api/v1/orders/{id}
// 分頁格式和 user-service、product-service 的列表一致
public record GetOrdersResDto(
        List<Item> items,

        // 從 0 開始
        int page,

        int size,

        long totalElements,

        int totalPages) {

    public record Item(
            UUID id,

            UUID userId,

            OrderStatus status,

            BigDecimal totalAmount,

            Instant createdAt) {

        static Item from(Order order) {
            return new Item(order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                    order.getCreatedAt());
        }
    }

    public static GetOrdersResDto from(Page<Order> orderPage) {
        return new GetOrdersResDto(
                orderPage.getContent().stream().map(Item::from).toList(),
                orderPage.getNumber(),
                orderPage.getSize(),
                orderPage.getTotalElements(),
                orderPage.getTotalPages());
    }
}

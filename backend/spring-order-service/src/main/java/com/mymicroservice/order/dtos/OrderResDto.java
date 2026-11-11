package com.mymicroservice.order.dtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mymicroservice.order.entities.Order;
import com.mymicroservice.order.entities.OrderItem;
import com.mymicroservice.order.enums.OrderStatus;

// 訂單詳情；不含 reservationStatus 等內部同步用的欄位
public record OrderResDto(
        UUID id,

        UUID userId,

        OrderStatus status,

        BigDecimal totalAmount,

        // 依 SKU 排序
        List<Item> items,

        Instant createdAt,

        Instant paidAt,

        Instant shippedAt,

        Instant completedAt,

        Instant cancelledAt) {

    public record Item(
            UUID productId,

            String sku,

            // 下單當下的名稱與單價
            String productName,

            BigDecimal unitPrice,

            int qty,

            BigDecimal subtotal) {

        static Item from(OrderItem item) {
            return new Item(item.getProductId(), item.getSku(), item.getProductName(), item.getUnitPrice(),
                    item.getQty(), item.getSubtotal());
        }
    }

    public static OrderResDto from(Order order) {
        return new OrderResDto(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getItems().stream().map(Item::from).toList(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.getShippedAt(),
                order.getCompletedAt(),
                order.getCancelledAt());
    }
}

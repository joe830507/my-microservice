package com.mymicroservice.order.dtos;

import java.math.BigDecimal;
import java.util.UUID;

import com.mymicroservice.order.entities.Order;
import com.mymicroservice.order.enums.OrderStatus;

// 給 payment-service：付款金額一律以這裡的 totalAmount 為準，不採用前端傳入的值
public record InternalOrderResDto(
        UUID id,

        UUID userId,

        OrderStatus status,

        BigDecimal totalAmount) {

    public static InternalOrderResDto from(Order order) {
        return new InternalOrderResDto(order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount());
    }
}

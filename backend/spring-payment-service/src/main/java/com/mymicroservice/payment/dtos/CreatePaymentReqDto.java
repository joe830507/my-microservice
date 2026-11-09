package com.mymicroservice.payment.dtos;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

// 只帶訂單 id；金額一律取自 order-service
public record CreatePaymentReqDto(
        @NotNull UUID orderId) {
}

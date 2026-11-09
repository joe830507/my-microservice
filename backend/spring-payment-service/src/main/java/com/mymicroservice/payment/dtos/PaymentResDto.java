package com.mymicroservice.payment.dtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mymicroservice.payment.entities.Payment;
import com.mymicroservice.payment.enums.PaymentStatus;

// 不含 orderNotification 等內部同步用的欄位
public record PaymentResDto(
        UUID id,

        UUID orderId,

        BigDecimal amount,

        PaymentStatus status,

        Instant createdAt,

        Instant completedAt) {

    public static PaymentResDto from(Payment payment) {
        return new PaymentResDto(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus(),
                payment.getCreatedAt(), payment.getCompletedAt());
    }
}

package com.mymicroservice.payment.entities;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.Hibernate;

import com.mymicroservice.common.entity.CommonEntity;
import com.mymicroservice.payment.enums.OrderNotification;
import com.mymicroservice.payment.enums.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// 狀態轉換（回呼、通知結果）用 PaymentRepository 的條件式 UPDATE，不透過實體修改
@Getter
@Setter
@Entity
@Table(schema = "payment_service", name = "payment")
public class Payment extends CommonEntity {

    // 由 service 產生（UUIDv7）
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    // 付款的人，必須是訂單的擁有者
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // 一律取自 order-service，不接受前端傳入
    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_notification")
    private OrderNotification orderNotification;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        Payment other = (Payment) o;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}

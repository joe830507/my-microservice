package com.mymicroservice.payment.repositories;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.payment.entities.Payment;
import com.mymicroservice.payment.enums.OrderNotification;
import com.mymicroservice.payment.enums.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // 進行中或成功的付款最多一筆（uq_payment_order_active）
    Optional<Payment> findFirstByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);

    // 金流回呼：只有 PENDING 才會改，回傳 1 = 成功、0 = 已經有結果
    // 成功時 orderNotification 設成 PENDING（待通知 order-service），失敗時維持 null
    // 條件式 UPDATE 會鎖住這一筆，重複或同時回呼只有一個生效；批次 UPDATE 不經過實體，version 與稽核欄位要自己更新
    @Transactional
    @Modifying
    @Query("""
            UPDATE Payment p
            SET p.status = :status, p.orderNotification = :orderNotification, p.completedAt = :now,
                p.version = p.version + 1, p.updatedAt = :now, p.updatedBy = :updatedBy
            WHERE p.id = :id AND p.status = com.mymicroservice.payment.enums.PaymentStatus.PENDING
            """)
    int completeIfPending(@Param("id") UUID id, @Param("status") PaymentStatus status,
            @Param("orderNotification") OrderNotification orderNotification, @Param("now") Instant now,
            @Param("updatedBy") UUID updatedBy);

    // 通知 order-service 的結果；只有還在 PENDING 的才會改
    @Transactional
    @Modifying
    @Query("""
            UPDATE Payment p SET p.orderNotification = :result
            WHERE p.id = :id AND p.orderNotification = com.mymicroservice.payment.enums.OrderNotification.PENDING
            """)
    int markOrderNotification(@Param("id") UUID id, @Param("result") OrderNotification result);

    // OrderNotificationRetryJob：還沒通知 order-service 成功的付款；使用 idx_payment_notification_pending
    // 只挑最後更新超過一段時間的，避免和正在通知的回呼請求重複
    @Query("""
            SELECT p.id FROM Payment p
            WHERE p.orderNotification = com.mymicroservice.payment.enums.OrderNotification.PENDING
              AND p.updatedAt < :cutoff
            ORDER BY p.updatedAt
            """)
    List<UUID> findPendingNotificationUpdatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}

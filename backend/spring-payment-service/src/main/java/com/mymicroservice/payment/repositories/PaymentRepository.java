package com.mymicroservice.payment.repositories;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.payment.entities.Payment;
import com.mymicroservice.payment.enums.PaymentStatus;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // 與回呼共用 PENDING 條件；只有先取得資料列鎖的一方可以完成付款。
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
            SET p.status = com.mymicroservice.payment.enums.PaymentStatus.CANCELLED,
                p.completedAt = :now, p.version = p.version + 1,
                p.updatedAt = :now, p.updatedBy = :updatedBy
            WHERE p.orderId = :orderId AND p.status = com.mymicroservice.payment.enums.PaymentStatus.PENDING
            """)
    int cancelPendingByOrderId(@Param("orderId") UUID orderId, @Param("now") Instant now,
            @Param("updatedBy") UUID updatedBy);

    // 進行中或成功的付款最多一筆（uq_payment_order_active）
    Optional<Payment> findFirstByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);

    // 金流回呼：只有 PENDING 才會改，回傳 1 = 成功、0 = 已經有結果
    // 條件式 UPDATE 會鎖住這一筆，重複或同時回呼只有一個生效；批次 UPDATE 不經過實體，version 與稽核欄位要自己更新
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Payment p
            SET p.status = :status, p.completedAt = :now,
                p.version = p.version + 1, p.updatedAt = :now, p.updatedBy = :updatedBy
            WHERE p.id = :id AND p.status = com.mymicroservice.payment.enums.PaymentStatus.PENDING
            """)
    int completeIfPending(@Param("id") UUID id, @Param("status") PaymentStatus status,
            @Param("now") Instant now,
            @Param("updatedBy") UUID updatedBy);

}

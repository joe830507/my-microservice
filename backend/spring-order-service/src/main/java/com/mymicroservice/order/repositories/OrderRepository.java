package com.mymicroservice.order.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.order.entities.Order;
import com.mymicroservice.order.enums.ReservationStatus;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    // 與 findById 相同，但一次撈回明細
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    // 建單的冪等重送：同一個使用者、同一個 key 的訂單
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    // 取消：只有 PENDING_PAYMENT 才會改，回傳 1 = 成功、0 = 狀態不對
    // 條件式 UPDATE 會鎖住這一筆：和付款通知同時送達時，後到的重新判斷條件，只有一個會成功
    // 批次 UPDATE 不經過實體，@Version 與稽核欄位要自己更新
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = com.mymicroservice.order.enums.OrderStatus.CANCELLED, o.cancelledAt = :now,
                o.reservationStatus = com.mymicroservice.order.enums.ReservationStatus.RELEASED,
                o.version = o.version + 1, o.updatedAt = :now, o.updatedBy = :updatedBy
            WHERE o.id = :id AND o.status = com.mymicroservice.order.enums.OrderStatus.PENDING_PAYMENT
            """)
    int cancelIfPending(@Param("id") UUID id, @Param("now") Instant now, @Param("updatedBy") UUID updatedBy);

    // 付款成功：只有 PENDING_PAYMENT 才會改，回傳 1 = 成功、0 = 狀態不對
    // 同時把預扣標成「應該確認、尚未同步」；和取消同時送達時只有一個會成功
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = com.mymicroservice.order.enums.OrderStatus.PAID, o.paidAt = :now,
                o.reservationStatus = com.mymicroservice.order.enums.ReservationStatus.CONFIRMED,
                o.version = o.version + 1, o.updatedAt = :now, o.updatedBy = :updatedBy
            WHERE o.id = :id AND o.status = com.mymicroservice.order.enums.OrderStatus.PENDING_PAYMENT
            """)
    int markPaidIfPending(@Param("id") UUID id, @Param("now") Instant now, @Param("updatedBy") UUID updatedBy);

    // 出貨：只有 PAID 才會改，回傳 1 = 成功、0 = 狀態不對；兩個請求同時出貨時只有一個會成功
    // 預扣在付款時已經確認，出貨不用通知 inventory
    @Transactional
    @Modifying
    @Query("""
            UPDATE Order o
            SET o.status = com.mymicroservice.order.enums.OrderStatus.SHIPPED, o.shippedAt = :now,
                o.version = o.version + 1, o.updatedAt = :now, o.updatedBy = :updatedBy
            WHERE o.id = :id AND o.status = com.mymicroservice.order.enums.OrderStatus.PAID
            """)
    int markShippedIfPaid(@Param("id") UUID id, @Param("now") Instant now, @Param("updatedBy") UUID updatedBy);

    // 完成：只有 SHIPPED 才會改，回傳 1 = 成功、0 = 狀態不對
    @Transactional
    @Modifying
    @Query("""
            UPDATE Order o
            SET o.status = com.mymicroservice.order.enums.OrderStatus.COMPLETED, o.completedAt = :now,
                o.version = o.version + 1, o.updatedAt = :now, o.updatedBy = :updatedBy
            WHERE o.id = :id AND o.status = com.mymicroservice.order.enums.OrderStatus.SHIPPED
            """)
    int markCompletedIfShipped(@Param("id") UUID id, @Param("now") Instant now,
            @Param("updatedBy") UUID updatedBy);

    // PaymentTimeoutJob：建立超過一段時間仍未付款的訂單，最舊的先處理；使用 idx_orders_status_created
    @Query("""
            SELECT o.id FROM Order o
            WHERE o.status = com.mymicroservice.order.enums.OrderStatus.PENDING_PAYMENT AND o.createdAt < :cutoff
            ORDER BY o.createdAt
            """)
    List<UUID> findPendingCreatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}

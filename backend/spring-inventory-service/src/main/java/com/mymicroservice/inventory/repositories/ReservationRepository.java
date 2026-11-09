package com.mymicroservice.inventory.repositories;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mymicroservice.inventory.entities.Reservation;
import com.mymicroservice.inventory.enums.ReservationStatus;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    // 與 findById 相同，但一次撈回明細；回應、冪等比對都需要
    @EntityGraph(attributePaths = "items")
    Optional<Reservation> findWithItemsByOrderId(UUID orderId);

    // 狀態轉換：只有目前是 from 才改成 to，回傳 1 = 成功、0 = 狀態不對或不存在
    // 會鎖住這一筆，兩個請求同時轉換時，後到的等前一個 commit 後重新判斷條件，只有一個會成功
    @Modifying
    @Query("""
            UPDATE Reservation r
            SET r.status = :to, r.version = r.version + 1, r.updatedAt = :now, r.updatedBy = :updatedBy
            WHERE r.orderId = :orderId AND r.status = :from
            """)
    int transition(@Param("orderId") UUID orderId, @Param("from") ReservationStatus from,
            @Param("to") ReservationStatus to, @Param("now") Instant now, @Param("updatedBy") UUID updatedBy);
}

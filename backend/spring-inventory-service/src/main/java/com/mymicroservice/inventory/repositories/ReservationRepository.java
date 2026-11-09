package com.mymicroservice.inventory.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.inventory.entities.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    // 與 findById 相同，但一次撈回明細；回應、冪等比對都需要
    @EntityGraph(attributePaths = "items")
    Optional<Reservation> findWithItemsByOrderId(UUID orderId);
}

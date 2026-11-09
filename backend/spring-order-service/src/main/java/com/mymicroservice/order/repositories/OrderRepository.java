package com.mymicroservice.order.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.order.entities.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    // 與 findById 相同，但一次撈回明細
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);
}

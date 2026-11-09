package com.mymicroservice.order.repositories;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mymicroservice.order.entities.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {
}

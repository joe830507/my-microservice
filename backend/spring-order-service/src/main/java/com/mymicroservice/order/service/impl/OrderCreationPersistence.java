package com.mymicroservice.order.service.impl;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.events.OutboxEventWriter;
import com.mymicroservice.order.entities.Order;
import com.mymicroservice.order.repositories.OrderRepository;

@Service
public class OrderCreationPersistence {
    private final OrderRepository orders;
    private final OutboxEventWriter outbox;

    public OrderCreationPersistence(OrderRepository orders, OutboxEventWriter outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional
    public void saveAndPublishCreated(Order order) {
        orders.saveAndFlush(order);
        outbox.append("order.events", "OrderCreated", order.getId().toString(),
                Map.of("userId", order.getUserId().toString(), "totalAmount", order.getTotalAmount()));
    }
}

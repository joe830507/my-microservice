package com.mymicroservice.notification.messaging;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mymicroservice.common.events.UuidV7;

@Component
public class EmailMessageQueue {
    private final JdbcTemplate jdbc;

    public EmailMessageQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(UUID eventId, String template, UUID orderId) {
        enqueue(eventId, template, orderId, null);
    }

    public void enqueue(UUID eventId, String template, UUID orderId, String reason) {
        enqueue(eventId, template, orderId, reason, null);
    }

    public void enqueue(UUID eventId, String template, UUID orderId, String reason, java.math.BigDecimal amount) {
        jdbc.update("INSERT INTO notification_service.email_message(id,event_id,template,order_id,reason,amount) "
                + "VALUES (?,?,?,?,?,?) ON CONFLICT (event_id,template) DO NOTHING",
                UuidV7.generate(), eventId, template, orderId, reason, amount);
    }
}

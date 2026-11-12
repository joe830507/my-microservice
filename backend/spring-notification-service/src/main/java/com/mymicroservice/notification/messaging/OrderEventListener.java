package com.mymicroservice.notification.messaging;

import java.math.BigDecimal;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.events.ProcessedEventHandler;
import com.mymicroservice.common.events.ResilientEventConsumer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderEventListener {
    private static final TypeReference<EventEnvelope<OrderEventPayload>> EVENT_TYPE = new TypeReference<>() {};
    private final ObjectMapper mapper;
    private final ProcessedEventHandler processedEvents;
    private final JdbcTemplate jdbc;
    private final EmailMessageQueue messages;
    private final ResilientEventConsumer resilient;

    public OrderEventListener(ObjectMapper mapper, ProcessedEventHandler processedEvents, JdbcTemplate jdbc,
            EmailMessageQueue messages, ResilientEventConsumer resilient) {
        this.mapper = mapper;
        this.processedEvents = processedEvents;
        this.jdbc = jdbc;
        this.messages = messages;
        this.resilient = resilient;
    }

    @KafkaListener(topics = "order.events", groupId = "notification-service")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        resilient.consume(record, () -> {
            EventEnvelope<OrderEventPayload> event = mapper.readValue(record.value(), EVENT_TYPE);
            validateEnvelope(event);
            UUID orderId = uuid(event.aggregateId(), "aggregate id");
            OrderEventPayload payload = event.payload();
            if (payload.orderId() != null && !orderId.equals(uuid(payload.orderId(), "order id"))) {
                throw new IllegalArgumentException("Order event aggregate does not match order id");
            }
            switch (event.type()) {
                case "OrderCreated" -> {
                    UUID userId = uuid(payload.userId(), "user id");
                    BigDecimal amount = payload.totalAmount();
                    if (amount == null || amount.signum() < 0) throw new IllegalArgumentException("Invalid order amount");
                    processedEvents.handle(event.eventId(), event.type(), () -> {
                        jdbc.update("INSERT INTO notification_service.order_ref(order_id,user_id,total_amount) "
                                        + "VALUES (?,?,?) ON CONFLICT (order_id) DO UPDATE SET "
                                        + "user_id=EXCLUDED.user_id,total_amount=EXCLUDED.total_amount",
                                orderId, userId, amount);
                        messages.enqueue(event.eventId(), "order-created", orderId);
                    });
                }
                case "OrderPaid", "OrderShipped", "OrderCancelled" -> {
                    String template = switch (event.type()) {
                        case "OrderPaid" -> "order-paid";
                        case "OrderShipped" -> "order-shipped";
                        default -> "order-cancelled";
                    };
                    processedEvents.handle(event.eventId(), event.type(),
                            () -> messages.enqueue(event.eventId(), template, orderId,
                                    "OrderCancelled".equals(event.type()) ? payload.reason() : null));
                }
                default -> throw new IllegalArgumentException("Unsupported order event type");
            }
        });
    }

    private static void validateEnvelope(EventEnvelope<?> event) {
        if (event == null || event.eventId() == null || event.type() == null || event.version() != 1
                || event.occurredAt() == null || event.aggregateId() == null || event.payload() == null) {
            throw new IllegalArgumentException("Invalid order event envelope");
        }
    }

    private static UUID uuid(String value, String field) {
        try { return UUID.fromString(value); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid order event " + field, invalid); }
    }
}

package com.mymicroservice.notification.messaging;

import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.events.ProcessedEventHandler;
import com.mymicroservice.common.events.ResilientEventConsumer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class UserEventListener {
    private static final TypeReference<EventEnvelope<UserRegisteredPayload>> EVENT_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ProcessedEventHandler processedEvents;
    private final JdbcTemplate jdbc;
    private final ResilientEventConsumer resilient;

    public UserEventListener(ObjectMapper objectMapper, ProcessedEventHandler processedEvents,
            JdbcTemplate jdbc, ResilientEventConsumer resilient) {
        this.objectMapper = objectMapper;
        this.processedEvents = processedEvents;
        this.jdbc = jdbc;
        this.resilient = resilient;
    }

    @KafkaListener(topics = "user.events", groupId = "notification-service")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        resilient.consume(record, () -> {
            EventEnvelope<UserRegisteredPayload> event = objectMapper.readValue(record.value(), EVENT_TYPE);
            UserRegisteredPayload payload = validate(event);
            UUID userId = UUID.fromString(payload.userId());

            processedEvents.handle(event.eventId(), event.type(), () -> jdbc.update(
                    "INSERT INTO notification_service.user_contact(user_id, email, display_name, updated_at) "
                            + "VALUES (?, ?, ?, now()) ON CONFLICT (user_id) DO UPDATE SET "
                            + "email = EXCLUDED.email, display_name = EXCLUDED.display_name, updated_at = now()",
                    userId, payload.email(), payload.displayName()));
        });
    }

    private UserRegisteredPayload validate(EventEnvelope<UserRegisteredPayload> event) {
        if (event == null || event.eventId() == null || !"UserRegistered".equals(event.type())
                || event.version() != 1 || event.occurredAt() == null || event.aggregateId() == null
                || event.payload() == null) {
            throw new IllegalArgumentException("Invalid UserRegistered event envelope");
        }

        UserRegisteredPayload payload = event.payload();
        if (payload.userId() == null || payload.email() == null || payload.displayName() == null) {
            throw new IllegalArgumentException("UserRegistered event is missing required fields");
        }

        UUID userId;
        UUID aggregateId;
        try {
            userId = UUID.fromString(payload.userId());
            aggregateId = UUID.fromString(event.aggregateId());
        } catch (IllegalArgumentException invalidId) {
            throw new IllegalArgumentException("UserRegistered event contains an invalid user id", invalidId);
        }
        if (!userId.equals(aggregateId) || payload.email().isBlank() || payload.displayName().isBlank()) {
            throw new IllegalArgumentException("UserRegistered event contains inconsistent or blank user data");
        }
        return payload;
    }
}

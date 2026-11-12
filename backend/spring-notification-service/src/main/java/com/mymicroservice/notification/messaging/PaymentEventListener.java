package com.mymicroservice.notification.messaging;

import java.math.BigDecimal;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.events.ProcessedEventHandler;
import com.mymicroservice.common.events.ResilientEventConsumer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class PaymentEventListener {
    private static final TypeReference<EventEnvelope<PaymentEventPayload>> EVENT_TYPE = new TypeReference<>() {};
    private final ObjectMapper mapper;
    private final ProcessedEventHandler processedEvents;
    private final EmailMessageQueue messages;
    private final ResilientEventConsumer resilient;

    public PaymentEventListener(ObjectMapper mapper, ProcessedEventHandler processedEvents,
            EmailMessageQueue messages, ResilientEventConsumer resilient) {
        this.mapper = mapper;
        this.processedEvents = processedEvents;
        this.messages = messages;
        this.resilient = resilient;
    }

    @KafkaListener(topics = "payment.events", groupId = "notification-service")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        resilient.consume(record, () -> {
            EventEnvelope<PaymentEventPayload> event = mapper.readValue(record.value(), EVENT_TYPE);
            if (event != null && "PaymentSucceeded".equals(event.type())) return;
            if (event == null || event.eventId() == null || !"PaymentFailed".equals(event.type())
                    || event.version() != 1 || event.occurredAt() == null || event.aggregateId() == null
                    || event.payload() == null) {
                throw new IllegalArgumentException("Invalid payment event envelope");
            }
            UUID orderId;
            try {
                orderId = UUID.fromString(event.aggregateId());
                if (event.payload().orderId() == null || !orderId.equals(UUID.fromString(event.payload().orderId()))) {
                    throw new IllegalArgumentException("Payment event aggregate does not match order id");
                }
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid payment event order id", invalid);
            }
            BigDecimal amount = event.payload().amount();
            if (event.payload().paymentId() == null || amount == null || amount.signum() < 0
                    || !"FAILED".equals(event.payload().status())) {
                throw new IllegalArgumentException("Invalid failed payment payload");
            }
            processedEvents.handle(event.eventId(), event.type(),
                    () -> messages.enqueue(event.eventId(), "payment-failed", orderId, null, amount));
        });
    }
}

package com.mymicroservice.payment.messaging;

import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.mymicroservice.common.audit.HeaderAuditorAware;
import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.events.ProcessedEventHandler;
import com.mymicroservice.common.events.ResilientEventConsumer;
import com.mymicroservice.payment.repositories.PaymentRepository;
import tools.jackson.databind.ObjectMapper;

/** 訂單取消時只收斂仍在等待的付款，不發出付款失敗事件。 */
@Component
public class OrderEventListener {
    private final ObjectMapper mapper;
    private final ProcessedEventHandler processedEvents;
    private final ResilientEventConsumer resilient;
    private final PaymentRepository payments;

    public OrderEventListener(ObjectMapper mapper, ProcessedEventHandler processedEvents,
            ResilientEventConsumer resilient, PaymentRepository payments) {
        this.mapper = mapper;
        this.processedEvents = processedEvents;
        this.resilient = resilient;
        this.payments = payments;
    }

    @KafkaListener(topics = "order.events", groupId = "payment-service")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        resilient.consume(record, () -> {
            EventEnvelope<?> event = mapper.readValue(record.value(), EventEnvelope.class);
            if (event == null || event.type() == null) throw new IllegalArgumentException("Invalid order event");
            // 本服務只訂閱取消語意；其他既有與未來事件明確忽略，不送 DLT。
            if (!"OrderCancelled".equals(event.type())) return;
            if (event.eventId() == null || event.version() != 1 || event.occurredAt() == null
                    || event.aggregateId() == null) throw new IllegalArgumentException("Invalid cancellation event");
            UUID orderId = UUID.fromString(event.aggregateId());
            processedEvents.handle(event.eventId(), event.type(), () ->
                    payments.cancelPendingByOrderId(orderId, Instant.now(), HeaderAuditorAware.SYSTEM_USER_ID));
        });
    }
}

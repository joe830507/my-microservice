package com.mymicroservice.inventory.messaging;

import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.events.ProcessedEventHandler;
import com.mymicroservice.common.events.ResilientEventConsumer;
import com.mymicroservice.inventory.service.ReservationService;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderEventListener {
    private final ObjectMapper objectMapper;
    private final ProcessedEventHandler processedEvents;
    private final ReservationService reservations;
    private final ResilientEventConsumer resilient;

    public OrderEventListener(ObjectMapper objectMapper, ProcessedEventHandler processedEvents,
            ReservationService reservations, ResilientEventConsumer resilient) {
        this.objectMapper = objectMapper;
        this.processedEvents = processedEvents;
        this.reservations = reservations;
        this.resilient = resilient;
    }

    @KafkaListener(topics = "order.events", groupId = "inventory-service")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        resilient.consume(record, () -> {
            EventEnvelope<?> event = objectMapper.readValue(record.value(), EventEnvelope.class);
            UUID orderId = UUID.fromString(event.aggregateId());
            // 庫存預留以 orderId 操作；事件去重與確認／釋放預留須一起提交。
            processedEvents.handle(event.eventId(), event.type(), () -> {
                switch (event.type()) {
                    case "OrderPaid" -> reservations.confirm(orderId);
                    case "OrderCancelled" -> reservations.release(orderId);
                    default -> throw new IllegalArgumentException("Unsupported order event " + event.type());
                }
            });
        });
    }
}

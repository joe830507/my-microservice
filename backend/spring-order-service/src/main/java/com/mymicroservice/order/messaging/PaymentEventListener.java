package com.mymicroservice.order.messaging;

import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.events.ProcessedEventHandler;
import com.mymicroservice.common.events.ResilientEventConsumer;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.order.exception.OrderErrorCode;
import com.mymicroservice.order.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Component
@Slf4j
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true")
public class PaymentEventListener {
    private final ObjectMapper objectMapper;
    private final ProcessedEventHandler processedEvents;
    private final OrderService orderService;
    private final ResilientEventConsumer resilient;

    public PaymentEventListener(ObjectMapper objectMapper, ProcessedEventHandler processedEvents,
            OrderService orderService, ResilientEventConsumer resilient) {
        this.objectMapper = objectMapper;
        this.processedEvents = processedEvents;
        this.orderService = orderService;
        this.resilient = resilient;
    }

    @KafkaListener(topics = "payment.events", groupId = "order-service")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        resilient.consume("payment.events", record.key(), record.value(), () -> {
            EventEnvelope<?> event = objectMapper.readValue(record.value(), EventEnvelope.class);
            UUID orderId = UUID.fromString(event.aggregateId());
            // 去重紀錄、訂單狀態與 markPaid 產生的 outbox 事件共用交易。
            processedEvents.handle(event.eventId(), event.type(), () -> {
                if ("PaymentSucceeded".equals(event.type())) {
                    try {
                        orderService.markPaid(orderId);
                    } catch (AppException failure) {
                        // 付款與取消競爭時，退款追蹤屬於訂單業務；重拋讓交易回滾並轉入 DLT。
                        if (failure.getErrorCode() == OrderErrorCode.ORDER_ALREADY_CANCELLED) {
                            log.error("Payment received for cancelled order; manual refund review required. orderId={}, eventId={}",
                                    orderId, event.eventId());
                        }
                        throw failure;
                    }
                } else if (!"PaymentFailed".equals(event.type())) {
                    throw new IllegalArgumentException("Unsupported payment event " + event.type());
                }
            });
        });
    }
}

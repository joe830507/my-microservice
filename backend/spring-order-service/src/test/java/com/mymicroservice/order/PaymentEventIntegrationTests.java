package com.mymicroservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.events.ResilientEventConsumer.EventRejectedException;
import com.mymicroservice.order.messaging.PaymentEventListener;
import com.mymicroservice.order.service.OrderService;

import tools.jackson.databind.ObjectMapper;

/** 真實資料庫驗證事件模式，停用 Kafka listener 自動啟動以隔離 broker 依賴。 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000"})
@Import(TestcontainersConfiguration.class)
class PaymentEventIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentEventListener listener;
    @Autowired OrderService orders;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clear() {
        jdbc.execute("TRUNCATE order_service.orders, order_service.outbox_event, order_service.processed_event CASCADE");
    }

    @Test
    void duplicatePaymentProducesOnePaidEventWithoutCallingInventory() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbc, UUID.randomUUID(), "PENDING_PAYMENT");
        String payload = mapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), "PaymentSucceeded",
                1, Instant.now(), orderId.toString(), Map.of("orderId", orderId)));
        var record = new ConsumerRecord<>("payment.events", 0, 0, orderId.toString(), payload);
        listener.consume(record);
        listener.consume(record);
        assertThat(jdbc.queryForObject("SELECT status FROM order_service.orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE event_type = 'OrderPaid'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void cancelledOrderRejectsPaymentWithoutCommittingDedupeOrPaidEvent() throws Exception {
        UUID orderId = OrderTestData.insertOrder(jdbc, UUID.randomUUID(), "CANCELLED");
        UUID eventId = UUID.randomUUID();
        String payload = mapper.writeValueAsString(new EventEnvelope<>(eventId, "PaymentSucceeded",
                1, Instant.now(), orderId.toString(), Map.of("orderId", orderId)));
        var record = new ConsumerRecord<>("payment.events", 0, 0, orderId.toString(), payload);
        assertThatThrownBy(() -> listener.consume(record)).isInstanceOf(EventRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_service.processed_event WHERE event_id = ?", Long.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE event_type = 'OrderPaid'", Long.class)).isZero();
    }

    @Test
    void userCancellationAndTimeoutEachWriteCancellationEvent() {
        UUID user = UUID.randomUUID();
        UUID first = OrderTestData.insertOrder(jdbc, user, "PENDING_PAYMENT");
        UUID second = OrderTestData.insertOrder(jdbc, user, "PENDING_PAYMENT");
        orders.cancelOrder(first, user, false);
        orders.expireUnpaidOrder(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_service.outbox_event WHERE event_type = 'OrderCancelled'", Long.class))
                .isEqualTo(2);
    }
}

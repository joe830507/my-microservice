package com.mymicroservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.payment.messaging.OrderEventListener;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000", "payment.mock-callback.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderCancellationIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderEventListener listener;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @Test
    void cancellationIsAtomicAndIdempotent() throws Exception {
        UUID order = UUID.randomUUID();
        UUID payment = PaymentTestData.insertPendingPayment(jdbc, order, UUID.randomUUID());
        UUID event = UUID.randomUUID();
        var record = record(event, order, "OrderCancelled");
        listener.consume(record);
        listener.consume(record);
        assertThat(paymentStatus(payment)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT version FROM payment_service.payment WHERE id=?", Long.class, payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM payment_service.payment WHERE id=?", Boolean.class, payment)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_service.processed_event WHERE event_id=?", Long.class, event)).isEqualTo(1);
        assertNoOutbox(order);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void preservesTerminalPayments(String status) throws Exception {
        UUID order = UUID.randomUUID();
        UUID payment = PaymentTestData.insertPayment(jdbc, order, UUID.randomUUID(), status, Instant.now());
        listener.consume(record(UUID.randomUUID(), order, "OrderCancelled"));
        assertThat(paymentStatus(payment)).isEqualTo(status);
        assertNoOutbox(order);
    }

    @Test
    void missingPaymentIsAcknowledged() throws Exception {
        UUID event = UUID.randomUUID();
        listener.consume(record(event, UUID.randomUUID(), "OrderCancelled"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_service.processed_event WHERE event_id=?", Long.class, event)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OrderCreated", "OrderPaid", "OrderShipped", "FutureOrderEvent"})
    void ignoresOtherTypes(String type) throws Exception {
        UUID order = UUID.randomUUID();
        UUID payment = PaymentTestData.insertPendingPayment(jdbc, order, UUID.randomUUID());
        listener.consume(record(UUID.randomUUID(), order, type));
        assertThat(paymentStatus(payment)).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void lateCallbackReturnsConflictWithoutEvent(String result) throws Exception {
        UUID order = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID payment = PaymentTestData.insertPendingPayment(jdbc, order, user);
        listener.consume(record(UUID.randomUUID(), order, "OrderCancelled"));
        mvc.perform(post("/api/v1/payments/{id}/mock-callback", payment)
                .header(UserContextHeaders.USER_ID, user.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"" + result + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_ALREADY_COMPLETED"));
        assertThat(paymentStatus(payment)).isEqualTo("CANCELLED");
        assertNoOutbox(order);
    }

    private ConsumerRecord<String, String> record(UUID event, UUID order, String type) {
        return new ConsumerRecord<>("order.events", 0, 0, order.toString(), mapper.writeValueAsString(
                new EventEnvelope<>(event, type, 1, Instant.now(), order.toString(), Map.of("orderId", order.toString()))));
    }

    private String paymentStatus(UUID payment) {
        return jdbc.queryForObject("SELECT status FROM payment_service.payment WHERE id=?", String.class, payment);
    }

    private void assertNoOutbox(UUID order) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_service.outbox_event WHERE aggregate_id=?", Long.class, order.toString())).isZero();
    }
}

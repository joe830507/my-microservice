package com.mymicroservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mymicroservice.payment.dtos.MockCallbackReqDto;
import com.mymicroservice.payment.enums.MockPaymentResult;
import com.mymicroservice.payment.service.PaymentService;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000", "payment.services.order-uri=http://localhost:1"})
@Import(TestcontainersConfiguration.class)
class PaymentOutboxIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;

    @Test
    void callbackCommitsPaymentAndEventWhileOrderIsUnavailable() {
        UUID user = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID payment = PaymentTestData.insertPendingPayment(jdbc, order, user);
        var callback = new MockCallbackReqDto(MockPaymentResult.SUCCESS);
        payments.mockCallback(payment, user, false, callback);
        payments.mockCallback(payment, user, false, callback);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_service.payment WHERE id = ?", String.class, payment))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_service.outbox_event WHERE aggregate_id = ? AND event_type = 'PaymentSucceeded'", Long.class, order.toString()))
                .isEqualTo(1);
    }
}

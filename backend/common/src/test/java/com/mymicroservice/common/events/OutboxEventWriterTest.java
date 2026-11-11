package com.mymicroservice.common.events;

import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

class OutboxEventWriterTest {
    @Test
    void appendCreatesUuidV7AndPersistsEnvelopeInOutbox() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":\"id\"}");
        OutboxEventWriter writer = new OutboxEventWriter(jdbc, objectMapper, "payment_service");

        var eventId = writer.append("payment.events", "PaymentSucceeded", "order-123",
                Map.of("orderId", "order-123"));

        assertThat(eventId.version()).isEqualTo(7);
        verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());
    }
}

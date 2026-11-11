package com.mymicroservice.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.inventory.dtos.reservation.ReserveReqDto;
import com.mymicroservice.inventory.messaging.OrderEventListener;
import com.mymicroservice.inventory.service.ReservationService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.events.enabled=true", "spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000"})
@Import(TestcontainersConfiguration.class)
class OrderEventIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReservationService reservations;
    @Autowired OrderEventListener listener;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest
    @CsvSource({"OrderPaid,CONFIRMED,7", "OrderCancelled,RELEASED,10"})
    void orderEventUpdatesReservationOnce(String type, String status, int available) throws Exception {
        jdbc.execute("TRUNCATE inventory_service.reservation, inventory_service.stock, inventory_service.processed_event CASCADE");
        UUID system = new UUID(0, 0);
        jdbc.update("INSERT INTO inventory_service.stock(sku,available,reserved,created_by,updated_by) VALUES('EVENT-SKU',10,0,?,?)", system, system);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(new ReserveReqDto(orderId, List.of(new ReserveReqDto.Item("EVENT-SKU", 3))));
        String payload = mapper.writeValueAsString(new EventEnvelope<>(UUID.randomUUID(), type, 1,
                Instant.now(), orderId.toString(), Map.of("orderId", orderId)));
        var record = new ConsumerRecord<>("order.events", 0, 0, orderId.toString(), payload);
        listener.consume(record);
        listener.consume(record);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_service.reservation WHERE order_id = ?", String.class, orderId))
                .isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT available FROM inventory_service.stock WHERE sku = 'EVENT-SKU'", Integer.class))
                .isEqualTo(available);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory_service.stock WHERE sku = 'EVENT-SKU'", Integer.class)).isZero();
    }
}

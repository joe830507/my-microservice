package com.mymicroservice.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RetireReservationSyncMigrationTests {
    @Test
    void upgradesUnsentLegacyStatesWithoutDuplicatingExistingEvent() {
        try (var postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("order_service").target("7").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID paid = UUID.randomUUID();
            UUID cancelled = UUID.randomUUID();
            UUID existing = UUID.randomUUID();
            for (UUID id : new UUID[]{paid, cancelled, existing}) {
                jdbc.update("INSERT INTO order_service.orders(id,user_id,status,reservation_status,reservation_synced,total_amount,created_by,updated_by) VALUES (?,?,?,?,false,10,?,?)",
                        id, id, id.equals(paid) ? "PAID" : "CANCELLED", id.equals(paid) ? "CONFIRMED" : "RELEASED", id, id);
            }
            jdbc.update("INSERT INTO order_service.outbox_event(event_id,topic,event_type,aggregate_id,occurred_at,payload) VALUES (?,'order.events','OrderCancelled',?,now(),'{}'::jsonb)",
                    UUID.randomUUID(), existing.toString());
            Flyway.configure().dataSource(source).schemas("order_service").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_service.outbox_event", Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT payload->>'type' FROM order_service.outbox_event WHERE aggregate_id=?", String.class, paid.toString())).isEqualTo("OrderPaid");
            assertThat(jdbc.queryForObject("SELECT payload->>'type' FROM order_service.outbox_event WHERE aggregate_id=?", String.class, cancelled.toString())).isEqualTo("OrderCancelled");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='order_service' AND table_name='orders' AND column_name='reservation_synced'", Long.class)).isZero();
        }
    }
}

package com.mymicroservice.payment;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class RetireOrderNotificationMigrationTests {
    @Test
    void upgradesPendingLegacyPaymentWithoutDuplicatingExistingEvent() {
        try (var postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("payment_service").target("5").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID payment = UUID.randomUUID();
            UUID existing = UUID.randomUUID();
            for (UUID id : new UUID[]{payment, existing}) {
                jdbc.update("INSERT INTO payment_service.payment(id,order_id,user_id,amount,status,order_notification,completed_at,created_by,updated_by) VALUES (?,?,?,10,'SUCCEEDED','PENDING',now(),?,?)",
                        id, id, id, id, id);
            }
            jdbc.update("INSERT INTO payment_service.outbox_event(event_id,topic,event_type,aggregate_id,occurred_at,payload) VALUES (?,'payment.events','PaymentSucceeded',?,now(),jsonb_build_object('payload',jsonb_build_object('paymentId',?)))",
                    UUID.randomUUID(), existing.toString(), existing.toString());
            Flyway.configure().dataSource(source).schemas("payment_service").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_service.outbox_event", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT payload->>'type' FROM payment_service.outbox_event WHERE aggregate_id=?", String.class, payment.toString())).isEqualTo("PaymentSucceeded");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='payment_service' AND table_name='payment' AND column_name='order_notification'", Long.class)).isZero();
        }
    }
}

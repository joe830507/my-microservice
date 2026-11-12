package com.mymicroservice.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.micrometer.core.instrument.MeterRegistry;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.mymicroservice.common.events.EventEnvelope;
import com.mymicroservice.inventory.TestcontainersConfiguration;

import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.events.outbox-poll-interval=3600000"
})
@Import(TestcontainersConfiguration.class)
class OrderEventKafkaIntegrationTests {
    private static final String ORDER_TOPIC = "order.events";
    private static final String ORDER_DLT = "order.events.DLT";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry metrics;

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(ORDER_TOPIC, 3, (short) 1),
                    new NewTopic(ORDER_DLT, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void prepare() {
        jdbc.execute("TRUNCATE inventory_service.processed_event");
        listeners.start();
    }

    @Test
    void createdAndShippedEventsAreRecordedAndIgnoredWithoutDlt() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID createdEvent = UUID.randomUUID();
        UUID shippedEvent = UUID.randomUUID();
        double dltBefore = dltCount();

        kafka.send(ORDER_TOPIC, orderId.toString(), event(createdEvent, "OrderCreated", orderId))
                .get(10, TimeUnit.SECONDS);
        kafka.send(ORDER_TOPIC, orderId.toString(), event(shippedEvent, "OrderShipped", orderId))
                .get(10, TimeUnit.SECONDS);

        await(() -> processed(createdEvent) && processed(shippedEvent), Duration.ofSeconds(15));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_service.processed_event WHERE event_id IN (?, ?)",
                Integer.class, createdEvent, shippedEvent)).isEqualTo(2);
        assertThat(dltCount()).isEqualTo(dltBefore);
    }

    @Test
    void unsupportedOrderEventIsPublishedToDeadLetterTopic() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "inventory-order-dlt-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(config)) {
            dlt.subscribe(List.of(ORDER_DLT));
            dlt.poll(Duration.ofMillis(250));
            dlt.seekToBeginning(dlt.assignment());
            kafka.send(ORDER_TOPIC, orderId.toString(), event(eventId, "UnknownOrderEvent", orderId))
                    .get(10, TimeUnit.SECONDS);

            assertThat(awaitDlt(dlt, eventId, Duration.ofSeconds(15))).isTrue();
            assertThat(processed(eventId)).isFalse();
        }
    }

    private String event(UUID eventId, String type, UUID orderId) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope<>(eventId, type, 1, Instant.now(),
                orderId.toString(), Map.of("orderId", orderId.toString())));
    }

    private boolean processed(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_service.processed_event WHERE event_id = ?",
                Integer.class, eventId) == 1;
    }

    private double dltCount() {
        return metrics.counter("events.dlt.published").count();
    }

    private static void await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static boolean awaitDlt(KafkaConsumer<String, String> consumer, UUID eventId, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(250))) {
                if (eventId.toString().equals(new ObjectMapper().readTree(record.value()).get("eventId").asText())) {
                    return true;
                }
            }
        }
        return false;
    }
}

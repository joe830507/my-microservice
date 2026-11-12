package com.mymicroservice.notification.messaging;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000"
})
@Import(TestcontainersConfiguration.class)
class UserEventIntegrationTests {
    private static final String USER_TOPIC = "user.events";
    private static final String USER_DLT = "user.events.DLT";

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

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(USER_TOPIC, 3, (short) 1),
                    new NewTopic(USER_DLT, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void clearData() {
        jdbc.execute("TRUNCATE notification_service.processed_event, notification_service.user_contact");
        listeners.start();
    }

    @Test
    void duplicateEventUpdatesContactOnlyOnce() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String event = event(eventId, userId, "first@example.com", "First Name");

        kafka.send(USER_TOPIC, userId.toString(), event).get(10, TimeUnit.SECONDS);
        kafka.send(USER_TOPIC, userId.toString(), event).get(10, TimeUnit.SECONDS);

        await(() -> count("processed_event", eventId) == 1
                && count("user_contact", userId) == 1, Duration.ofSeconds(15));
        assertThat(jdbc.queryForObject("SELECT email FROM notification_service.user_contact WHERE user_id = ?",
                String.class, userId)).isEqualTo("first@example.com");
    }

    @Test
    void laterRegistrationEventForSameUserReplacesContactData() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID firstEvent = UUID.randomUUID();
        UUID latestEvent = UUID.randomUUID();

        kafka.send(USER_TOPIC, userId.toString(), event(firstEvent, userId, "old@example.com", "Old Name"))
                .get(10, TimeUnit.SECONDS);
        kafka.send(USER_TOPIC, userId.toString(), event(latestEvent, userId, "latest@example.com", "Latest Name"))
                .get(10, TimeUnit.SECONDS);

        await(() -> count("processed_event", firstEvent) == 1 && count("processed_event", latestEvent) == 1,
                Duration.ofSeconds(15));
        assertThat(jdbc.queryForObject(
                "SELECT email || '|' || display_name FROM notification_service.user_contact WHERE user_id = ?",
                String.class, userId)).isEqualTo("latest@example.com|Latest Name");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_service.user_contact WHERE user_id = ?",
                Long.class, userId)).isEqualTo(1L);
    }

    @Test
    void missingRequiredFieldIsPublishedToDeadLetterTopic() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String invalid = "{\"eventId\":\"" + eventId + "\",\"type\":\"UserRegistered\","
                + "\"version\":1,\"occurredAt\":\"" + Instant.now() + "\",\"aggregateId\":\"" + userId
                + "\",\"payload\":{\"userId\":\"" + userId + "\",\"displayName\":\"Missing Email\"}}";

        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-dlt-test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(config)) {
            dlt.subscribe(List.of(USER_DLT));
            dlt.poll(Duration.ofMillis(250));
            dlt.seekToBeginning(dlt.assignment());

            kafka.send(USER_TOPIC, userId.toString(), invalid).get(10, TimeUnit.SECONDS);
            boolean found = awaitDlt(dlt, eventId, Duration.ofSeconds(15));

            assertThat(found).isTrue();
            assertThat(count("processed_event", eventId)).isZero();
            assertThat(count("user_contact", userId)).isZero();
        }
    }

    private String event(UUID eventId, UUID userId, String email, String displayName) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "eventId", eventId,
                "type", "UserRegistered",
                "version", 1,
                "occurredAt", Instant.now(),
                "aggregateId", userId.toString(),
                "payload", Map.of("userId", userId.toString(), "email", email, "displayName", displayName)));
    }

    private long count(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_service." + table
                + (table.equals("processed_event") ? " WHERE event_id = ?" : " WHERE user_id = ?"), Long.class, id);
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
            var records = consumer.poll(Duration.ofMillis(250));
            for (var record : records) {
                var envelope = new ObjectMapper().readTree(record.value());
                if (eventId.toString().equals(envelope.get("eventId").asText())) return true;
            }
        }
        return false;
    }
}

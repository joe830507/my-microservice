package com.mymicroservice.common.events;

import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class OutboxRelayIntegrationTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static OutboxEventWriter writer;
    static OutboxRelay relay;
    static KafkaTemplate<String, String> kafkaTemplate;
    static DefaultKafkaProducerFactory<String, String> producerFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE SCHEMA event_test");
        jdbc.execute("CREATE TABLE event_test.outbox_event (sequence_id BIGSERIAL, event_id UUID PRIMARY KEY, topic VARCHAR(120) NOT NULL, "
                + "event_type VARCHAR(120) NOT NULL, aggregate_id VARCHAR(120) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL, "
                + "payload JSONB NOT NULL, traceparent VARCHAR(100), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "published_at TIMESTAMPTZ)");
        jdbc.execute("CREATE INDEX idx_event_test_pending ON event_test.outbox_event (occurred_at) WHERE published_at IS NULL");
        jdbc.execute("CREATE TABLE event_test.processed_event(event_id UUID PRIMARY KEY, event_type TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE event_test.business_counter(id INT PRIMARY KEY, value INT NOT NULL)");
        jdbc.execute("INSERT INTO event_test.business_counter VALUES(1, 0)");

        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("payment.events", 3, (short) 1),
                    new NewTopic("retry-test", 1, (short) 1),
                    new NewTopic("retry-test.DLT", 1, (short) 1))).all().get();
        }

        producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(),
                "max.block.ms", 3000,
                "key.serializer", StringSerializer.class,
                "value.serializer", StringSerializer.class));
        kafkaTemplate = new KafkaTemplate<>(producerFactory);
        ObjectMapper mapper = new ObjectMapper();
        writer = new OutboxEventWriter(jdbc, mapper, "event_test");
        OutboxMetrics metrics = new OutboxMetrics(new SimpleMeterRegistry());
        relay = new OutboxRelay(jdbc, kafkaTemplate, transactions, metrics, "event_test");
    }

    @AfterAll
    static void closeProducer() {
        if (kafkaTemplate != null) kafkaTemplate.destroy();
    }

    @Test
    void concurrentDuplicatesUpdateBusinessOnlyOnceAndFailureRollsBackDedupe() throws Exception {
        var handler = new ProcessedEventHandler(jdbc, transactions, "event_test");
        UUID eventId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                start.await();
                return handler.handle(eventId, "test", () -> jdbc.update(
                        "UPDATE event_test.business_counter SET value = value + 1 WHERE id = 1"));
            })).toList();
            start.countDown();
            int applied = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) applied++;
            assertThat(applied).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT value FROM event_test.business_counter WHERE id = 1", Integer.class)).isEqualTo(1);
        UUID failed = UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(failed, "test", () -> {
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(handler.handle(failed, "test", () -> {})).isTrue();
    }

    @Test
    void invalidRecordGoesToDltAndNextRecordInPartitionIsProcessed() throws Exception {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "retry-test-group",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var properties = new ContainerProperties("retry-test");
        CountDownLatch processed = new CountDownLatch(1);
        properties.setMessageListener((MessageListener<String, String>) record -> {
            if (record.value().equals("invalid")) throw new IllegalArgumentException("invalid event");
            processed.countDown();
        });
        var container = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, String>(config), properties);
        container.setCommonErrorHandler(new EventsAutoConfiguration().kafkaErrorHandler(
                kafkaTemplate, new SimpleMeterRegistry()));
        container.start();
        try (KafkaConsumer<String, String> dlt = new KafkaConsumer<>(config)) {
            dlt.subscribe(List.of("retry-test.DLT"));
            kafkaTemplate.send("retry-test", "key", "invalid").get(10, TimeUnit.SECONDS);
            kafkaTemplate.send("retry-test", "key", "valid").get(10, TimeUnit.SECONDS);
            assertThat(processed.await(30, TimeUnit.SECONDS)).isTrue();
            boolean received = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!received && System.nanoTime() < deadline) {
                for (var record : dlt.poll(Duration.ofMillis(250))) {
                    received |= record.value().equals("invalid");
                }
            }
            assertThat(received).isTrue();
        } finally {
            container.stop();
        }
    }

    @Test
    void rolledBackBusinessTransactionDoesNotPublishAndCommittedOutboxIsDelivered() throws Exception {
        transactions.executeWithoutResult(status -> {
            writer.append("payment.events", "PaymentSucceeded", "order-rollback", Map.of("orderId", "order-rollback"));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_test.outbox_event", Long.class)).isZero();

        writer.append("payment.events", "PaymentSucceeded", "order-123", Map.of("orderId", "order-123"));
        producerFactory.updateConfigs(Map.of("bootstrap.servers", "localhost:1"));
        producerFactory.reset();
        relay.publishPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_test.outbox_event WHERE published_at IS NULL", Long.class))
                .isEqualTo(1L);

        producerFactory.updateConfigs(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
        producerFactory.reset();
        relay.publishPending();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_test.outbox_event WHERE published_at IS NOT NULL", Long.class))
                .isEqualTo(1L);

        Properties consumerConfig = new Properties();
        consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-test");
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig)) {
            consumer.subscribe(List.of("payment.events"));
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            var found = false;
            while (!found && System.nanoTime() < deadline) {
                found = !consumer.poll(Duration.ofMillis(250)).isEmpty();
            }
            assertThat(found).isTrue();
        }
    }
}

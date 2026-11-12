package com.mymicroservice.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionTemplate;
import com.mymicroservice.notification.mail.EmailDispatcher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "app.events.outbox-poll-interval=3600000",
        "notification.dispatch.interval=100ms"
})
@Import(TestcontainersConfiguration.class)
class NotificationEmailIntegrationTests {
    private static final String ORDER_TOPIC = "order.events";
    private static final String PAYMENT_TOPIC = "payment.events";
    private static final String USER_TOPIC = "user.events";
    private static final org.slf4j.Logger MAILPIT_LOG = org.slf4j.LoggerFactory.getLogger("test.mailpit");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    @Container static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.33.1"))
            .withEnv("MP_SMTP_BIND_ADDR", "0.0.0.0:1025")
            .withExposedPorts(1025, 8025)
            .withLogConsumer(new Slf4jLogConsumer(MAILPIT_LOG))
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forHttp("/api/v1/info").forPort(8025).forStatusCode(200))
                    .withStrategy(Wait.forListeningPort()));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.mail.host", MAILPIT::getHost);
        properties.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @Autowired JavaMailSenderImpl mailSender;
    @Autowired TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(USER_TOPIC, 3, (short) 1),
                    new NewTopic(ORDER_TOPIC, 3, (short) 1),
                    new NewTopic(ORDER_TOPIC + ".DLT", 3, (short) 1),
                    new NewTopic(PAYMENT_TOPIC, 3, (short) 1),
                    new NewTopic(PAYMENT_TOPIC + ".DLT", 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void clear() throws Exception {
        jdbc.execute("TRUNCATE notification_service.processed_event, notification_service.user_contact, "
                + "notification_service.order_ref, notification_service.email_message");
        request("DELETE", "/api/v1/messages");
        listeners.start();
    }

    @Test
    void eventsQueueDeduplicatedMessagesAndDeliverAllTemplatesThroughMailpit() throws Exception {
        assertThat(mailSender.getHost()).isEqualTo(MAILPIT.getHost());
        assertThat(mailSender.getPort()).isEqualTo(MAILPIT.getMappedPort(1025));

        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID paidEventId = UUID.randomUUID();

        // Payment can arrive before OrderCreated and UserRegistered; it must wait and send later.
        publish(ORDER_TOPIC, orderId, event(UUID.randomUUID(), "OrderPaid", orderId,
                Map.of("orderId", orderId.toString())));
        publish(PAYMENT_TOPIC, orderId, event(UUID.randomUUID(), "PaymentSucceeded", orderId,
                Map.of("paymentId", UUID.randomUUID().toString(), "orderId", orderId.toString(),
                        "status", "SUCCEEDED", "amount", 42.50)));
        publish(USER_TOPIC, userId, event(UUID.randomUUID(), "UserRegistered", userId,
                Map.of("userId", userId.toString(), "email", "buyer@example.com",
                        "displayName", "Alice\r\nBcc: attacker@example.com")));
        publish(ORDER_TOPIC, orderId, event(UUID.randomUUID(), "OrderCreated", orderId,
                Map.of("userId", userId.toString(), "totalAmount", 42.50)));
        String paid = event(paidEventId, "OrderPaid", orderId, Map.of("orderId", orderId.toString()));
        publish(ORDER_TOPIC, orderId, paid);
        publish(ORDER_TOPIC, orderId, paid);
        publish(ORDER_TOPIC, orderId, event(UUID.randomUUID(), "OrderShipped", orderId,
                Map.of("orderId", orderId.toString())));
        publish(ORDER_TOPIC, orderId, event(UUID.randomUUID(), "OrderCancelled", orderId,
                Map.of("orderId", orderId.toString(), "reason", "timeout")));
        publish(PAYMENT_TOPIC, orderId, event(UUID.randomUUID(), "PaymentFailed", orderId,
                Map.of("paymentId", UUID.randomUUID().toString(), "orderId", orderId.toString(),
                        "status", "FAILED", "amount", 42.50)));

        await(() -> jdbc.queryForObject("SELECT count(*) FROM notification_service.email_message WHERE status='SENT'",
                        Long.class) == 6L, Duration.ofSeconds(30), () -> "email rows=" + jdbc.queryForList(
                                "SELECT template,status,attempts,last_error FROM notification_service.email_message")
                                + "; processed=" + jdbc.queryForList("SELECT event_type FROM notification_service.processed_event"));
        await(() -> mailMessages().path("messages").size() == 6, Duration.ofSeconds(10),
                () -> "Mailpit response=" + mailMessages());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_service.email_message "
                + "WHERE event_id=? AND template='order-paid'", Long.class, paidEventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_service.processed_event "
                + "WHERE event_type='PaymentSucceeded'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_service.email_message "
                + "WHERE template='payment-succeeded'", Long.class)).isZero();

        JsonNode mails = mailMessages().path("messages");
        assertThat(mails).hasSize(6);
        java.util.Set<String> subjects = new java.util.HashSet<>();
        for (JsonNode mailSummary : mails) {
            JsonNode mail = request("GET", "/api/v1/message/" + mailSummary.path("ID").asText());
            assertThat(mail.path("To").toString()).contains("buyer@example.com");
            subjects.add(mail.path("Subject").asText());
            assertThat(mail.path("Text").asText()).contains(orderId.toString());
            assertThat(mail.path("Text").asText()).doesNotContain("Bcc:", "attacker@example.com");
        }
        assertThat(subjects).containsExactlyInAnyOrder("訂單已成立", "付款成功", "訂單已出貨", "訂單已取消", "付款失敗");
        assertThat(mails.toString()).doesNotContain("attacker@example.com");
    }

    @Test
    void concurrentDispatcherInstancesDoNotSendTheSameMessageTwice() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_service.user_contact(user_id,email,display_name) VALUES (?,?,?)",
                userId, "parallel@example.com", "Parallel Buyer");
        jdbc.update("INSERT INTO notification_service.order_ref(order_id,user_id,total_amount) VALUES (?,?,?)",
                orderId, userId, new java.math.BigDecimal("8.25"));
        jdbc.update("INSERT INTO notification_service.email_message(id,event_id,template,order_id) VALUES (?,?,?,?)",
                UUID.randomUUID(), eventId, "order-paid", orderId);

        EmailDispatcher first = new EmailDispatcher(jdbc, transactions, mailSender,
                "no-reply@my-microservice.local", 10);
        EmailDispatcher second = new EmailDispatcher(jdbc, transactions, mailSender,
                "no-reply@my-microservice.local", 10);
        CompletableFuture.allOf(
                CompletableFuture.runAsync(first::dispatchDueEmails),
                CompletableFuture.runAsync(second::dispatchDueEmails)).join();

        await(() -> mailMessages().path("messages").size() == 1, Duration.ofSeconds(10),
                () -> "Mailpit response=" + mailMessages());
        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("SENT");
    }

    private void publish(String topic, UUID key, String value) throws Exception {
        kafka.send(topic, key.toString(), value).get(10, TimeUnit.SECONDS);
    }

    private String event(UUID eventId, String type, UUID aggregateId, Map<String, ?> payload) throws Exception {
        return mapper.writeValueAsString(Map.of("eventId", eventId, "type", type, "version", 1,
                "occurredAt", Instant.now(), "aggregateId", aggregateId.toString(), "payload", payload));
    }

    private JsonNode mailMessages() {
        try { return request("GET", "/api/v1/messages"); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private JsonNode request(String method, String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":"
                + MAILPIT.getMappedPort(8025) + path));
        if (method.equals("DELETE")) builder.DELETE(); else builder.GET();
        HttpRequest request = builder.build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        if (method.equals("DELETE")) return mapper.createObjectNode();
        return response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    private static void await(BooleanSupplier condition, Duration timeout) {
        await(condition, timeout, () -> "condition not satisfied");
    }

    private static void await(BooleanSupplier condition, Duration timeout,
            java.util.function.Supplier<String> diagnostic) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
        }
        assertThat(condition.getAsBoolean()).as(diagnostic.get()).isTrue();
    }
}

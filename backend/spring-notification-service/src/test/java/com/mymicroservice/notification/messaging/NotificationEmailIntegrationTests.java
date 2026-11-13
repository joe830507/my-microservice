package com.mymicroservice.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import io.micrometer.core.instrument.MeterRegistry;
import com.mymicroservice.notification.mail.EmailDispatcher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.BindMode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "app.events.outbox-poll-interval=3600000",
        "notification.dispatch.interval=3600000"
})
@Import(TestcontainersConfiguration.class)
class NotificationEmailIntegrationTests {
    private static final String ORDER_TOPIC = "order.events";
    private static final String PAYMENT_TOPIC = "payment.events";
    private static final String USER_TOPIC = "user.events";
    private static final org.slf4j.Logger MAILPIT_LOG = org.slf4j.LoggerFactory.getLogger("test.mailpit");
    private static boolean dispatcherJarReady;
    private final List<GenericContainer<?>> dispatcherContainers = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final Network NETWORK = Network.newNetwork();
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");
    @Container static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.33.1"))
            .withEnv("MP_SMTP_BIND_ADDR", "0.0.0.0:1025")
            .withNetwork(NETWORK)
            .withNetworkAliases("mailpit")
            .withExposedPorts(8025)
            .withLogConsumer(new Slf4jLogConsumer(MAILPIT_LOG))
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forHttp("/api/v1/info").forPort(8025).forStatusCode(200))
                    .withStrategy(Wait.forListeningPort()));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", () -> 1);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JavaMailSenderImpl mailSender;
    @Autowired EmailDispatcher dispatcher;
    @Autowired MeterRegistry meters;
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

    @AfterAll
    static void closeNetwork() {
        NETWORK.close();
    }

    @BeforeEach
    void clear() throws Exception {
        jdbc.execute("TRUNCATE notification_service.processed_event, notification_service.user_contact, "
                + "notification_service.order_ref, notification_service.email_message");
        request("DELETE", "/api/v1/messages");
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(1);
        listeners.start();
    }

    @Test
    void eventsQueueDeduplicatedMessagesAndDeliverAllTemplatesThroughMailpit() throws Exception {
        GenericContainer<?> dispatcher = startDispatcher();

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
                        "displayName", "Alice\r\nBcc: attacker@example.com {{amount}}")));
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
        java.util.Set<String> messageIds = new java.util.HashSet<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id FROM notification_service.email_message WHERE status='SENT'")) {
            messageIds.add("<" + row.get("id") + "@my-microservice.local>");
        }
        for (JsonNode mailSummary : mails) {
            JsonNode mail = request("GET", "/api/v1/message/" + mailSummary.path("ID").asText());
            assertThat(mail.path("To").toString()).contains("buyer@example.com");
            subjects.add(mail.path("Subject").asText());
            String body = mail.path("Text").asText();
            assertThat(body).contains(orderId.toString());
            assertThat(body).contains("Alice Bcc: attacker@example.com {{amount}} 您好：");
            assertThat(mail.path("Bcc").isMissingNode() || mail.path("Bcc").isEmpty()).isTrue();
            String messageId = mail.path("MessageID").asText();
            assertThat(messageIds).contains("<" + messageId + ">");
            assertThat(messageIds.remove("<" + messageId + ">")).isTrue();
            JsonNode headers = request("GET", "/api/v1/message/" + mailSummary.path("ID").asText() + "/headers");
            assertThat(headers.toString().toLowerCase(java.util.Locale.ROOT)).contains("message-id");
            assertThat(headers.toString()).contains("<" + messageId + ">");
        }
        assertThat(messageIds).isEmpty();
        assertThat(subjects).containsExactlyInAnyOrder("訂單已成立", "付款成功", "訂單已出貨", "訂單已取消", "付款失敗");
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

        jdbc.update("UPDATE notification_service.email_message SET next_attempt_at=now() + interval '10 seconds' WHERE event_id=?", eventId);
        startDispatcher();
        startDispatcher();
        jdbc.update("UPDATE notification_service.email_message SET next_attempt_at=now() WHERE event_id=?", eventId);

        await(() -> mailMessages().path("messages").size() == 1, Duration.ofSeconds(10),
                () -> "Mailpit response=" + mailMessages());
        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("SENT");
        String expectedMessageId = "<" + jdbc.queryForObject(
                "SELECT id FROM notification_service.email_message WHERE event_id=?", UUID.class, eventId)
                + "@my-microservice.local>";
        JsonNode mail = request("GET", "/api/v1/message/" + mailMessages().path("messages").get(0).path("ID").asText());
        assertThat(mail.path("MessageID").asText()).isEqualTo(expectedMessageId.substring(1, expectedMessageId.length() - 1));
        JsonNode headers = request("GET", "/api/v1/message/" + mailMessages().path("messages").get(0).path("ID").asText()
                + "/headers");
        assertThat(headers.toString().toLowerCase(java.util.Locale.ROOT)).contains("message-id");
        assertThat(headers.toString()).contains(expectedMessageId);
    }

    @AfterEach
    void stopDispatchers() {
        dispatcherContainers.forEach(GenericContainer::stop);
        dispatcherContainers.clear();
    }

    @Test
    void missingOrderReferenceIsSkippedAfterFiveAttempts() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_service.email_message(id,event_id,template,order_id) VALUES (?,?,?,?)",
                UUID.randomUUID(), eventId, "order-paid", orderId);

        for (int i = 0; i < 5; i++) dispatchNow(eventId);

        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_service.email_message WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT skip_reason FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("unknown_order");
        assertThat(meters.counter("notification.emails", "template", "order-paid", "result", "skipped").count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void missingUserContactIsSkippedAfterFiveAttempts() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_service.order_ref(order_id,user_id,total_amount) VALUES (?,?,?)",
                orderId, userId, new java.math.BigDecimal("8.25"));
        jdbc.update("INSERT INTO notification_service.email_message(id,event_id,template,order_id) VALUES (?,?,?,?)",
                UUID.randomUUID(), eventId, "order-paid", orderId);

        for (int i = 0; i < 5; i++) dispatchNow(eventId);

        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT skip_reason FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("unknown_user");
    }

    @Test
    void transientSmtpFailureRetriesThenMarksFailedOnEighthAttempt() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_service.user_contact(user_id,email,display_name) VALUES (?,?,?)",
                userId, "smtp-failure@example.com", "SMTP Failure");
        jdbc.update("INSERT INTO notification_service.order_ref(order_id,user_id,total_amount) VALUES (?,?,?)",
                orderId, userId, new java.math.BigDecimal("8.25"));
        jdbc.update("INSERT INTO notification_service.email_message(id,event_id,template,order_id) VALUES (?,?,?,?)",
                UUID.randomUUID(), eventId, "order-paid", orderId);
        mailSender.setHost("127.0.0.1");
        mailSender.setPort(1);

        dispatchNow(eventId);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_service.email_message WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() FROM notification_service.email_message WHERE event_id=?",
                Boolean.class, eventId)).isTrue();
        for (int i = 1; i < 8; i++) dispatchNow(eventId);

        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_service.email_message WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT last_error FROM notification_service.email_message WHERE event_id=?",
                String.class, eventId)).isNotBlank();
        assertThat(meters.counter("notification.emails", "template", "order-paid", "result", "retry").count())
                .isGreaterThanOrEqualTo(1);
        assertThat(meters.counter("notification.emails", "template", "order-paid", "result", "failed").count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void expiredLeaseIsReclaimedButActiveLeaseIsUntouched() {
        UUID expired = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_service.email_message(id,event_id,template,status,lease_until,lease_token) "
                + "VALUES (?,?,'order-paid','SENDING',now()-interval '1 second',?), "
                + "(?,?,'order-paid','SENDING',now()+interval '1 minute',?)",
                expired, UUID.randomUUID(), UUID.randomUUID(), active, UUID.randomUUID(), UUID.randomUUID());
        dispatcher.dispatchDueEmails();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE id=?", String.class, expired))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM notification_service.email_message WHERE id=?", UUID.class, expired))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT attempts FROM notification_service.email_message WHERE id=?", Integer.class, active))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE id=?", String.class, active))
                .isEqualTo("SENDING");
    }

    @Test
    void smtpRunsOutsideTransactionAfterClaimHasCommitted() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbc.update("INSERT INTO notification_service.user_contact(user_id,email) VALUES (?,?)", userId, "buyer@example.com");
        jdbc.update("INSERT INTO notification_service.order_ref(order_id,user_id,total_amount) VALUES (?,?,10)", orderId, userId);
        jdbc.update("INSERT INTO notification_service.email_message(id,event_id,template,order_id) VALUES (?,?,'order-paid',?)",
                messageId, UUID.randomUUID(), orderId);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT status='SENDING' AND lease_until>now() AND lease_token IS NOT NULL "
                    + "FROM notification_service.email_message WHERE id=?", Boolean.class, messageId)).isTrue();
            return null;
        }).when(mailSender).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
        dispatcher.dispatchDueEmails();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_service.email_message WHERE id=?", String.class, messageId)).isEqualTo("SENT");
    }

    private void dispatchNow(UUID eventId) {
        jdbc.update("UPDATE notification_service.email_message SET next_attempt_at=now() WHERE event_id=? "
                + "AND status='PENDING'", eventId);
        dispatcher.dispatchDueEmails();
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

    private GenericContainer<?> startDispatcher() {
        ensureDispatcherJar();
        String backend = Path.of(System.getProperty("user.dir")).getParent().toAbsolutePath().toString();
        String kafka = KAFKA.getBootstrapServers().replace("localhost", "host.docker.internal")
                .replace("127.0.0.1", "host.docker.internal");
        String command = "exec java -jar /workspace/spring-notification-service/target/notification-service-0.0.1-SNAPSHOT.jar"
                + " --spring.datasource.url=jdbc:postgresql://notification-db:5432/test"
                + " --spring.datasource.username=" + TestcontainersConfiguration.POSTGRES.getUsername()
                + " --spring.datasource.password=" + TestcontainersConfiguration.POSTGRES.getPassword()
                + " --spring.mail.host=mailpit --spring.mail.port=1025"
                + " --spring.kafka.bootstrap-servers=" + kafka
                + " --spring.kafka.listener.auto-startup=false --spring.kafka.admin.auto-create=false"
                + " --app.events.outbox-poll-interval=3600000 --notification.dispatch.interval=100ms";
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("maven:3.9-eclipse-temurin-21"))
                .withNetwork(NETWORK)
                .withFileSystemBind(backend, "/workspace", BindMode.READ_WRITE)
                .withCommand("sh", "-c", command)
                .withStartupTimeout(Duration.ofMinutes(2))
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*Started NotificationServiceApplication.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))
                .withLogConsumer(new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("test.notification-dispatcher")));
        container.start();
        dispatcherContainers.add(container);
        return container;
    }

    private synchronized void ensureDispatcherJar() {
        if (dispatcherJarReady) return;
        String backend = Path.of(System.getProperty("user.dir")).getParent().toAbsolutePath().toString();
        Path jar = Path.of(backend, "spring-notification-service", "target", "notification-service-0.0.1-SNAPSHOT.jar");
        Path marker = jar.resolveSibling("notification-dispatcher-build.sha256");
        String fingerprint = dispatcherSourceFingerprint(Path.of(backend));
        if (java.nio.file.Files.isRegularFile(jar)) {
            try {
                if (java.nio.file.Files.isRegularFile(marker)
                        && java.nio.file.Files.readString(marker).equals(fingerprint)) {
                    dispatcherJarReady = true;
                    return;
                }
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not inspect the notification build marker", failure);
            }
        }
        String mavenRepo = Path.of(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath().toString();
        Path mavenDistribution = findMavenDistribution();
        GenericContainer<?> builder = new GenericContainer<>(DockerImageName.parse("maven:3.9-eclipse-temurin-21"))
                .withFileSystemBind(backend, "/workspace", BindMode.READ_WRITE)
                .withFileSystemBind(mavenRepo, "/root/.m2/repository", BindMode.READ_WRITE)
                .withFileSystemBind(mavenDistribution.toString(), "/opt/maven", BindMode.READ_ONLY)
                .withCommand("sh", "-c", "sh /opt/maven/bin/mvn -o -f /workspace/pom.xml -pl spring-notification-service -am -DskipTests package"
                        + " && echo NOTIFICATION_PACKAGE_COMPLETE && tail -f /dev/null")
                .withStartupTimeout(Duration.ofMinutes(10))
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*NOTIFICATION_PACKAGE_COMPLETE.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(10)))
                .withLogConsumer(new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("test.notification-package")));
        builder.start();
        builder.stop();
        try { java.nio.file.Files.writeString(marker, fingerprint); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Could not write notification build marker", failure); }
        dispatcherJarReady = true;
    }

    private static String dispatcherSourceFingerprint(Path backend) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            List<Path> roots = List.of(
                    backend.resolve("pom.xml"),
                    backend.resolve("common/pom.xml"), backend.resolve("common/src/main"),
                    backend.resolve("observability/pom.xml"), backend.resolve("observability/src/main"),
                    backend.resolve("spring-notification-service/pom.xml"),
                    backend.resolve("spring-notification-service/src/main"));
            List<Path> files = new java.util.ArrayList<>();
            for (Path root : roots) {
                if (java.nio.file.Files.isDirectory(root)) {
                    try (var paths = java.nio.file.Files.walk(root)) {
                        paths.filter(java.nio.file.Files::isRegularFile).forEach(files::add);
                    }
                } else if (java.nio.file.Files.isRegularFile(root)) files.add(root);
            }
            files.sort(Path::compareTo);
            for (Path file : files) {
                digest.update(backend.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(java.nio.file.Files.readAllBytes(file));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException | java.io.IOException failure) {
            throw new IllegalStateException("Could not fingerprint notification sources", failure);
        }
    }

    private static Path findMavenDistribution() {
        Path distributions = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists", "apache-maven-3.10.0");
        try (var candidates = java.nio.file.Files.list(distributions)) {
            return candidates.filter(java.nio.file.Files::isDirectory)
                    .filter(path -> java.nio.file.Files.isRegularFile(path.resolve("bin/mvn")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Maven 3.10.0 distribution is not cached"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not locate cached Maven distribution", failure);
        }
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

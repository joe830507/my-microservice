package com.mymicroservice.product.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.mymicroservice.product.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "search.rebuild.on-startup=false",
        "app.events.outbox-poll-interval=3600000"
})
@Import(TestcontainersConfiguration.class)
class ProductIndexEventListenerIntegrationTests {
    private static final int ELASTICSEARCH_HOST_PORT = 19200;

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0");

    @Container
    static final GenericContainer<?> ELASTICSEARCH = new FixedPortContainer(
            new ImageFromDockerfile("my-microservice/elasticsearch-d3-test:9.4.5-smartcn", false)
                    .withDockerfile(java.nio.file.Path.of("../../infra/elasticsearch/Dockerfile")),
            ELASTICSEARCH_HOST_PORT, 9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("node.store.allow_mmap", "false")
            .waitingFor(Wait.forLogMessage(".*started.*", 1));

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("search.elasticsearch.endpoint", () -> "http://localhost:" + ELASTICSEARCH_HOST_PORT);
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    ElasticsearchClient elasticsearch;

    @Autowired
    ProductIndexManager indexManager;

    @Autowired
    ProductIndexRebuildService rebuildService;

    @Autowired
    MeterRegistry metrics;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetSearchAlias() throws Exception {
        if (elasticsearch.indices().existsAlias(a -> a.name(ProductIndexManager.ALIAS)).value()) {
            var aliases = elasticsearch.indices().getAlias(a -> a.name(ProductIndexManager.ALIAS)).aliases();
            for (String index : aliases.keySet()) {
                elasticsearch.indices().delete(d -> d.index(index));
            }
        }
        indexManager.createIndex("products-d3-" + UUID.randomUUID().toString().replace("-", ""),
                ProductIndexManager.ALIAS);
    }

    @Test
    void outOfOrderProductEventsLeaveTheNewestSnapshotIndexed() throws Exception {
        String productId = UUID.randomUUID().toString();
        double indexedBefore = counter("indexed");
        double staleBefore = counter("stale_skipped");

        send(event(productId, 4, "newest"));
        send(event(productId, 3, "older"));
        await(() -> counter("indexed") > indexedBefore && counter("stale_skipped") > staleBefore,
                Duration.ofSeconds(10), "new and stale versions to be consumed");

        var document = elasticsearch.get(g -> g.index(ProductIndexManager.ALIAS).id(productId), ProductDocument.class)
                .source();
        assertThat(document.name()).isEqualTo("newest");
    }

    @Test
    void unsupportedProductEventIsSentToDeadLetterTopic() throws Exception {
        double dltBefore = metrics.counter("events.dlt.published").count();
        String invalid = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"ProductRemoved\",\"version\":1,\"occurredAt\":\"2026-11-11T00:00:00Z\","
                + "\"aggregateId\":\"" + UUID.randomUUID() + "\",\"payload\":{}}";

        kafka.send("product.events", UUID.randomUUID().toString(), invalid).get(5, TimeUnit.SECONDS);

        await(() -> metrics.counter("events.dlt.published").count() > dltBefore,
                Duration.ofSeconds(10), "invalid product event to reach DLT");
    }

    @Test
    void temporaryElasticsearchFailureRetriesUntilServiceRecoversWithoutDlt() throws Exception {
        String productId = UUID.randomUUID().toString();
        double indexedBefore = counter("indexed");
        double dltBefore = metrics.counter("events.dlt.published").count();

        ELASTICSEARCH.stop();
        send(event(productId, 1, "recovered"));
        boolean reachedDltWhileUnavailable = awaitUntil(() ->
                metrics.counter("events.dlt.published").count() > dltBefore, Duration.ofSeconds(3));
        assertThat(reachedDltWhileUnavailable).isFalse();

        ELASTICSEARCH.start();
        await(this::elasticsearchResponds, Duration.ofSeconds(20), "Elasticsearch HTTP API after restart");
        indexManager.createIndex("products-d3-recovered-" + UUID.randomUUID().toString().replace("-", ""),
                ProductIndexManager.ALIAS);
        await(() -> counter("indexed") > indexedBefore, Duration.ofSeconds(45), "event indexing after ES recovery");
        assertThat(metrics.counter("events.dlt.published").count()).isEqualTo(dltBefore);
        var document = elasticsearch.get(g -> g.index(ProductIndexManager.ALIAS).id(productId), ProductDocument.class)
                .source();
        assertThat(document.name()).isEqualTo("recovered");
    }

    @Test
    void missingAliasDuringInitialRebuildRetriesUntilIndexIsReady() throws Exception {
        String productId = UUID.randomUUID().toString();
        double indexedBefore = counter("indexed");
        double dltBefore = metrics.counter("events.dlt.published").count();
        for (String index : indexManager.indicesForAlias(ProductIndexManager.ALIAS)) {
            indexManager.deleteIndex(index);
        }

        send(event(productId, 1, "initial-index-ready"));
        boolean reachedDltWhileAliasMissing = awaitUntil(() ->
                metrics.counter("events.dlt.published").count() > dltBefore, Duration.ofSeconds(3));
        assertThat(reachedDltWhileAliasMissing).isFalse();

        rebuildService.rebuild(false);
        await(() -> counter("indexed") > indexedBefore, Duration.ofSeconds(20), "event indexing after initial rebuild");
        assertThat(metrics.counter("events.dlt.published").count()).isEqualTo(dltBefore);
        var document = elasticsearch.get(g -> g.index(ProductIndexManager.ALIAS).id(productId), ProductDocument.class)
                .source();
        assertThat(document.name()).isEqualTo("initial-index-ready");
    }

    private void send(String event) throws Exception {
        String productId = objectMapper.readTree(event).get("aggregateId").asText();
        kafka.send("product.events", productId, event).get(5, TimeUnit.SECONDS);
    }

    private static String event(String productId, long version, String name) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"ProductUpdated\",\"version\":1,"
                + "\"occurredAt\":\"2026-11-11T00:00:00Z\",\"aggregateId\":\"" + productId + "\",\"payload\":{"
                + "\"productId\":\"" + productId + "\",\"sku\":\"SKU-1\",\"name\":\"" + name + "\","
                + "\"description\":\"description\",\"brand\":\"brand\",\"createdAt\":\"2026-11-11T00:00:00Z\","
                + "\"price\":12.34,\"categoryId\":\"\",\"status\":\"ACTIVE\",\"version\":" + version + "}}";
    }

    private double counter(String result) {
        return metrics.counter("search.index.events", "result", result).count();
    }

    private boolean elasticsearchResponds() {
        try {
            return elasticsearch.ping().value();
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout, String expectation) {
        assertThat(awaitUntil(condition, timeout)).as("waiting for " + expectation).isTrue();
    }

    private static boolean awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        return condition.getAsBoolean();
    }

    private static class FixedPortContainer extends GenericContainer<FixedPortContainer> {
        FixedPortContainer(java.util.concurrent.Future<String> image, int hostPort, int containerPort) {
            super(image);
            addFixedExposedPort(hostPort, containerPort);
        }
    }
}

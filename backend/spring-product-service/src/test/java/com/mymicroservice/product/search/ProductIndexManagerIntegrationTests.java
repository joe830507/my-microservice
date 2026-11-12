package com.mymicroservice.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;

class ProductIndexManagerIntegrationTests {
    private static final String ALIAS = "products";
    private static final String INDEX = "products-d2-test";
    private static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
            new ImageFromDockerfile("my-microservice/elasticsearch-test:9.4.5-smartcn", false)
                    .withDockerfile(Path.of("../../infra/elasticsearch/Dockerfile")))
            .withExposedPorts(9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("node.store.allow_mmap", "false")
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    private static Rest5Client restClient;
    private static Rest5ClientTransport transport;
    private static ElasticsearchClient client;
    private static ProductIndexManager manager;

    @BeforeAll
    static void startElasticsearch() {
        ELASTICSEARCH.start();
        restClient = Rest5Client.builder(URI.create("http://" + ELASTICSEARCH.getHost() + ":"
                + ELASTICSEARCH.getMappedPort(9200))).build();
        transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
        manager = new ProductIndexManager(client);
    }

    @AfterAll
    static void stopElasticsearch() throws Exception {
        if (transport != null) {
            transport.close();
        }
        ELASTICSEARCH.stop();
    }

    @BeforeEach
    void createIndex() throws Exception {
        if (client.indices().exists(e -> e.index(INDEX)).value()) {
            client.indices().delete(d -> d.index(INDEX));
        }
        manager.createIndex(INDEX, ALIAS);
    }

    @Test
    void createsIndexWithAliasAndTracksBuiltMetadata() throws Exception {
        assertThat(client.indices().exists(e -> e.index(INDEX)).value()).isTrue();
        assertThat(client.indices().getAlias(a -> a.name(ALIAS)).aliases()).containsKey(INDEX);
        assertThat(manager.isBuilt(INDEX)).isFalse();

        manager.markBuilt(INDEX, true);

        assertThat(manager.isBuilt(INDEX)).isTrue();
    }

    @Test
    void externalGteRejectsOlderVersionsAndAcceptsSameOrNewerVersions() throws Exception {
        String productId = UUID.randomUUID().toString();
        ProductDocument first = document(productId, "第一版");
        assertThat(manager.write(ALIAS, first, 7)).isEqualTo(7);
        assertThat(manager.write(ALIAS, document(productId, "舊事件"), 6)).isEqualTo(-1);
        assertThat(manager.write(ALIAS, document(productId, "同版重送"), 7)).isEqualTo(7);
        assertThat(manager.write(ALIAS, document(productId, "最新版本"), 8)).isEqualTo(8);
        client.indices().refresh(r -> r.index(INDEX));

        ProductDocument indexed = client.get(g -> g.index(INDEX).id(first.id()), ProductDocument.class).source();
        assertThat(indexed.name()).isEqualTo("最新版本");
    }

    @Test
    void strictMappingRejectsUnknownFields() {
        Map<String, Object> invalidDocument = Map.of("id", UUID.randomUUID().toString(), "unexpected", "value");

        assertThatThrownBy(() -> client.index(i -> i.index(ALIAS).id("strict-check").document(invalidDocument)))
                .isInstanceOf(ElasticsearchException.class)
                .satisfies(exception -> assertThat(((ElasticsearchException) exception).status()).isEqualTo(400));
    }

    private static ProductDocument document(String id, String name) {
        return new ProductDocument(id, "SKU-1", name, "品牌", "描述", "",
                "ACTIVE", new BigDecimal("12.34"), "2026-11-11T00:00:00Z");
    }
}

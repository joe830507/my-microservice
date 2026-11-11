package com.mymicroservice.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonData;
import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.product.repository.ProductRepository;

@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "search.rebuild.on-startup=false",
        "app.events.outbox-poll-interval=3600000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductIndexRebuildIntegrationTests {
    private static final int ELASTICSEARCH_HOST_PORT = 19201;

    @Container
    static final GenericContainer<?> ELASTICSEARCH = new FixedPortContainer(
            new ImageFromDockerfile("my-microservice/elasticsearch-d4-test:9.4.5-smartcn", false)
                    .withDockerfile(Path.of("../../infra/elasticsearch/Dockerfile")),
            ELASTICSEARCH_HOST_PORT, 9200)
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withEnv("node.store.allow_mmap", "false")
            .waitingFor(Wait.forLogMessage(".*started.*", 1));

    @DynamicPropertySource
    static void elasticsearchEndpoint(DynamicPropertyRegistry properties) {
        properties.add("search.elasticsearch.endpoint", () -> "http://localhost:" + ELASTICSEARCH_HOST_PORT);
    }

    @Autowired ProductIndexRebuildService rebuildService;
    @Autowired ProductRepository productRepository;
    @Autowired ProductIndexStartupRebuilder startupRebuilder;
    @Autowired ProductIndexManager indexManager;
    @Autowired ElasticsearchClient elasticsearch;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mockMvc;
    @MockitoSpyBean ProductIndexManager indexManagerSpy;

    @BeforeEach
    void clearState() throws Exception {
        productRepository.deleteAllInBatch();
        for (String index : indexManager.indicesForAlias(ProductIndexManager.ALIAS)) {
            indexManager.deleteIndex(index);
        }
    }

    @Test
    void emptyDatabaseCreatesBuiltIndexAndInternalApiCanTriggerRebuild() throws Exception {
        mockMvc.perform(post("/internal/v1/products/search-index/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockAcquired").value(true))
                .andExpect(jsonPath("$.scannedDocuments").value(0));

        String aliasIndex = onlyAliasIndex();
        assertThat(indexManager.isBuilt(aliasIndex)).isTrue();
        assertThat(indexManager.schemaVersion(aliasIndex)).isEqualTo(ProductIndexManager.SCHEMA_VERSION);
    }

    @Test
    void keysetPagingIndexesMoreThanOneBatch() throws Exception {
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            products.add(product("bulk-" + i));
        }
        productRepository.saveAllAndFlush(products);

        ProductIndexRebuildService.RebuildResult result = rebuildService.rebuild(false);

        assertThat(result.scannedDocuments()).isEqualTo(501);
        assertThat(elasticsearch.exists(e -> e.index(ProductIndexManager.ALIAS).id(products.get(500).getId().toString()))
                .value()).isTrue();
    }

    @Test
    void changesDuringRebuildAreAppliedBySupplementPass() throws Exception {
        Product product = productRepository.saveAndFlush(product("before-rebuild"));
        jdbcTemplate.update("update product_service.product set updated_at = now() - interval '10 minutes' where id = ?",
                product.getId());

        CountDownLatch firstBulkComplete = new CountDownLatch(1);
        CountDownLatch continueRebuild = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (firstCall.compareAndSet(true, false)) {
                firstBulkComplete.countDown();
                if (!continueRebuild.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release rebuild test barrier");
                }
            }
            return result;
        }).when(indexManagerSpy).bulkWrite(anyString(), anyList(), anyList());

        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var rebuild = worker.submit(() -> rebuildService.rebuild(false));
            assertThat(firstBulkComplete.await(10, TimeUnit.SECONDS)).isTrue();
            Product changed = productRepository.findById(product.getId()).orElseThrow();
            changed.setName("changed-during-rebuild");
            productRepository.saveAndFlush(changed);
            continueRebuild.countDown();
            rebuild.get(15, TimeUnit.SECONDS);
        } finally {
            continueRebuild.countDown();
            worker.shutdownNow();
        }

        ProductDocument indexed = elasticsearch.get(g -> g.index(ProductIndexManager.ALIAS)
                .id(product.getId().toString()), ProductDocument.class).source();
        assertThat(indexed.name()).isEqualTo("changed-during-rebuild");
    }

    @Test
    void failedBulkDoesNotSwitchAlias() throws Exception {
        productRepository.saveAndFlush(product("bulk-failure"));
        String oldIndex = "products-d4-old-" + UUID.randomUUID().toString().replace("-", "");
        indexManager.createIndex(oldIndex, ProductIndexManager.ALIAS);
        doThrow(new IOException("simulated bulk item failure"))
                .when(indexManagerSpy).bulkWrite(anyString(), anyList(), anyList());

        assertThatThrownBy(() -> rebuildService.rebuild(false)).isInstanceOf(RuntimeException.class);

        assertThat(indexManager.indicesForAlias(ProductIndexManager.ALIAS)).containsExactly(oldIndex);
    }

    @Test
    void advisoryLockSkipsConcurrentRebuildInsteadOfWaiting() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, ProductIndexRebuildService.ADVISORY_LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertThat(result.getBoolean(1)).isTrue();
            }

            ProductIndexRebuildService.RebuildResult skipped = rebuildService.rebuild(false);
            assertThat(skipped.lockAcquired()).isFalse();

            try (PreparedStatement unlock = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                unlock.setLong(1, ProductIndexRebuildService.ADVISORY_LOCK_KEY);
                unlock.execute();
            }
        }
    }

    @Test
    void deleteOldRemovesPriorIndexOnlyWhenRequested() throws Exception {
        String oldIndex = "products-d4-old-" + UUID.randomUUID().toString().replace("-", "");
        indexManager.createIndex(oldIndex, ProductIndexManager.ALIAS);

        ProductIndexRebuildService.RebuildResult result = rebuildService.rebuild(true);

        assertThat(result.oldIndicesDeleted()).isTrue();
        assertThat(indexManager.indexExists(oldIndex)).isFalse();
        assertThat(indexManager.indicesForAlias(ProductIndexManager.ALIAS)).containsExactly(result.indexName());
    }

    @Test
    void startupRebuildsWhenAliasSchemaDoesNotMatch() throws Exception {
        String outdatedIndex = "products-d4-outdated-" + UUID.randomUUID().toString().replace("-", "");
        indexManager.createIndex(outdatedIndex, ProductIndexManager.ALIAS);
        elasticsearch.indices().putMapping(p -> p.index(outdatedIndex).meta(java.util.Map.of(
                "schemaVersion", JsonData.of(ProductIndexManager.SCHEMA_VERSION - 1),
                "built", JsonData.of(true))));

        startupRebuilder.rebuildIfNeeded();

        await(() -> {
            try {
                for (String index : indexManager.indicesForAlias(ProductIndexManager.ALIAS)) {
                    if (!index.equals(outdatedIndex)
                            && indexManager.schemaVersion(index) == ProductIndexManager.SCHEMA_VERSION) {
                        return true;
                    }
                }
                return false;
            } catch (IOException failure) {
                return false;
            }
        }, Duration.ofSeconds(15));
        assertThat(indexManager.indicesForAlias(ProductIndexManager.ALIAS)).doesNotContain(outdatedIndex);
    }

    private String onlyAliasIndex() throws IOException {
        return indexManager.indicesForAlias(ProductIndexManager.ALIAS).iterator().next();
    }

    private static Product product(String name) {
        Product product = new Product();
        product.setSku("SKU-" + UUID.randomUUID());
        product.setName(name);
        product.setBrand("Brand");
        product.setDescription("Description");
        product.setPrice(new BigDecimal("12.34"));
        product.setStatus(ProductStatus.ACTIVE);
        return product;
    }

    private static void await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static class FixedPortContainer extends GenericContainer<FixedPortContainer> {
        FixedPortContainer(java.util.concurrent.Future<String> image, int hostPort, int containerPort) {
            super(image);
            addFixedExposedPort(hostPort, containerPort);
        }
    }
}

package com.mymicroservice.product.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.mymicroservice.common.resilience.DownstreamCallers;
import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.entity.Product;
import com.mymicroservice.product.entity.ProductStatus;
import com.mymicroservice.product.repository.ProductRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// Elasticsearch 卡住（接受連線但永遠不回應）時：搜尋在讀取逾時後退回資料庫，回應格式與正常相同；
// 連續失敗後熔斷打開，之後不再等 Elasticsearch
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "search.rebuild.on-startup=false",
        "search.elasticsearch.readiness-cache-ttl=0s",
        "search.elasticsearch.query-read-timeout=300ms",
        "search.elasticsearch.query-resilience.sliding-window-size=4",
        "search.elasticsearch.query-resilience.minimum-number-of-calls=4",
        "search.elasticsearch.query-resilience.wait-duration-in-open-state=1h",
        "app.events.outbox-poll-interval=3600000"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductSearchFallbackIntegrationTests {
    private static final ServerSocket HANGING_ELASTICSEARCH = hangingServer();
    private static final List<Socket> ACCEPTED = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void elasticsearchEndpoint(DynamicPropertyRegistry properties) {
        properties.add("search.elasticsearch.endpoint", () -> "http://localhost:" + HANGING_ELASTICSEARCH.getLocalPort());
    }

    @Autowired MockMvc mockMvc;
    @Autowired ProductRepository productRepository;
    @Autowired DownstreamCallers callers;

    @BeforeEach
    void seed() {
        productRepository.deleteAllInBatch();
        Product product = new Product();
        product.setSku("SKU-" + UUID.randomUUID());
        product.setName("不鏽鋼保溫杯");
        product.setBrand("");
        product.setDescription("");
        product.setPrice(new BigDecimal("300.00"));
        product.setStatus(ProductStatus.ACTIVE);
        productRepository.saveAndFlush(product);
        callers.circuitBreakers().circuitBreaker(ProductSearchService.BREAKER_NAME).reset();
    }

    private static final SimpleMeterRegistry METRICS = new SimpleMeterRegistry();

    @BeforeAll
    static void attachMetrics() {
        Metrics.addRegistry(METRICS);
    }

    @AfterAll
    static void closeServer() throws IOException {
        Metrics.removeRegistry(METRICS);
        for (Socket socket : ACCEPTED) {
            socket.close();
        }
        HANGING_ELASTICSEARCH.close();
    }

    @Test
    void slowElasticsearchFallsBackToTheDatabaseWithTheSameResponseShape() throws Exception {
        double before = METRICS.counter("search.fallback", "reason", "timeout").count();

        mockMvc.perform(get("/api/v1/products").param("keyword", "保溫"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("不鏽鋼保溫杯"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        assertThat(METRICS.counter("search.fallback", "reason", "timeout").count()).isGreaterThan(before);
    }

    @Test
    void repeatedFailuresOpenTheBreakerAndLaterRequestsSkipElasticsearch() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(get("/api/v1/products").param("keyword", "保溫")).andExpect(status().isOk());
        }
        CircuitBreaker breaker = callers.circuitBreakers().circuitBreaker(ProductSearchService.BREAKER_NAME);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        double openBefore = METRICS.counter("search.fallback", "reason", "breaker_open").count();
        int connectionsBefore = ACCEPTED.size();
        long start = System.nanoTime();
        mockMvc.perform(get("/api/v1/products").param("keyword", "保溫"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        assertThat(METRICS.counter("search.fallback", "reason", "breaker_open").count()).isGreaterThan(openBefore);
        assertThat(ACCEPTED).as("熔斷打開後不再連 Elasticsearch").hasSize(connectionsBefore);
        assertThat(System.nanoTime() - start).as("不必再等讀取逾時").isLessThan(Duration.ofMillis(250).toNanos());
    }

    // 接受連線、什麼都不回：模擬 Elasticsearch 卡住
    private static ServerSocket hangingServer() {
        try {
            ServerSocket server = new ServerSocket(0);
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        ACCEPTED.add(server.accept());
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "hanging-elasticsearch");
            acceptor.setDaemon(true);
            acceptor.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

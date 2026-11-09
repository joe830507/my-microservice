package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

// Redis 連不上時放行：限流失效，但登入照常（和 user-service 的登入失敗計數一致）
// 健康檢查也維持 UP：Redis 只用來限流，不能讓它把 Gateway 標成 DOWN
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.login-rate-limit.replenish-rate=1",
        "gateway.login-rate-limit.burst-capacity=180",
        "gateway.login-rate-limit.requested-tokens=60" })
@AutoConfigureWebTestClient
class GatewayLoginRateLimitRedisDownIntegrationTests {

    private static final int LIMIT = 3;

    @RegisterExtension
    static final WireMockExtension USER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.user-uri", USER_SERVICE::baseUrl);
        // 沒有任何東西在聽的 port
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", GatewayLoginRateLimitRedisDownIntegrationTests::unusedPort);
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    @Test
    void redisUnavailable_loginIsAllowed() {
        for (int i = 0; i < LIMIT + 2; i++) {
            webTestClient.post().uri("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isOk();
        }

        USER_SERVICE.verify(LIMIT + 2, postRequestedFor(urlEqualTo("/api/v1/auth/login")));
    }

    @Test
    void redisUnavailable_healthIsStillUp() {
        webTestClient.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}

package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

// 註冊限流（SEC-04，直接用連線 IP，trusted-proxies = 0）：同一個 IP 超過上限回 429，和登入各自計數
// 測試的上限：一次 3 個請求，之後每 60 秒才補 1 個，測試期間不會補回來
// Redis 為 Testcontainers 的真實容器；每個測試前後清空，不影響其他測試類別的登入請求
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.register-rate-limit.replenish-rate=1",
        "gateway.register-rate-limit.burst-capacity=180",
        "gateway.register-rate-limit.requested-tokens=60" })
@AutoConfigureWebTestClient
class GatewayRegisterRateLimitIntegrationTests {

    private static final int LIMIT = 3;
    private static final String REGISTER_PATH = "/api/v1/auth/register"; private static final String LOGIN_PATH = "/api/v1/auth/login";

    @RegisterExtension
    static final WireMockExtension PRODUCT_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension USER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension JWKS_SERVER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.product-uri", PRODUCT_SERVICE::baseUrl);
        registry.add("gateway.services.user-uri", USER_SERVICE::baseUrl);
        registry.add("gateway.security.jwt.jwk-set-uri", () -> JWKS_SERVER.baseUrl() + "/jwks");
        TestRedis.register(registry);
    }

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        flushRedis();
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(TestJwts.jwks(TestJwts.SIGNING_KEY))));
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    @AfterEach
    void flushRedis() {
        redisTemplate.execute(connection -> connection.serverCommands().flushAll()).blockLast();
    }

    private WebTestClient.ResponseSpec register() {
        return webTestClient.post().uri(REGISTER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\": \"a@example.com\", \"password\": \"password123\", \"displayName\": \"A\"}")
                .exchange();
    }

    @Test
    void registerWithinLimit_isForwarded() {
        for (int i = 0; i < LIMIT; i++) {
            register().expectStatus().isOk();
        }

        USER_SERVICE.verify(LIMIT, postRequestedFor(urlEqualTo(REGISTER_PATH)));
    }

    @Test
    void registerOverLimit_returns429ProblemDetailWithoutCallingUserService() {
        for (int i = 0; i < LIMIT; i++) {
            register().expectStatus().isOk();
        }

        register().expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS");

        USER_SERVICE.verify(LIMIT, postRequestedFor(urlEqualTo(REGISTER_PATH)));
    }

    @Test
    void registerAndLogin_haveSeparateBuckets() {
        // 註冊的額度用完，不影響登入（登入用自己的 gateway.login-rate-limit 設定與 Redis key）
        for (int i = 0; i < LIMIT; i++) {
            register().expectStatus().isOk();
        }
        register().expectStatus().isEqualTo(429);

        webTestClient.post().uri(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\": \"a@example.com\", \"password\": \"password123\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void forgedXForwardedFor_doesNotGiveANewQuota() {
        for (int i = 0; i < LIMIT; i++) {
            webTestClient.post().uri(REGISTER_PATH)
                    .header("X-Forwarded-For", "203.0.113." + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.post().uri(REGISTER_PATH)
                .header("X-Forwarded-For", "203.0.113.99")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(429);
    }
}

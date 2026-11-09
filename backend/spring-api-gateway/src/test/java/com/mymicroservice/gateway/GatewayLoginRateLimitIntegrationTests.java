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

// 登入限流（直接用連線 IP，trusted-proxies = 0）：同一個 IP 超過上限回 429，其他路由不受影響
// 測試的上限：一次 3 個請求，之後每 60 秒才補 1 個，測試期間不會補回來
// Redis 為 Testcontainers 的真實容器；每個測試前後清空，不影響其他測試類別的登入請求
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.login-rate-limit.replenish-rate=1",
        "gateway.login-rate-limit.burst-capacity=180",
        "gateway.login-rate-limit.requested-tokens=60" })
@AutoConfigureWebTestClient
class GatewayLoginRateLimitIntegrationTests {

    private static final int LIMIT = 3;
    private static final String LOGIN_PATH = "/api/v1/auth/login";

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

    private WebTestClient.ResponseSpec login() {
        return webTestClient.post().uri(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\": \"alice@example.com\", \"password\": \"password123\"}")
                .exchange();
    }

    private void exhaustLimit() {
        for (int i = 0; i < LIMIT; i++) {
            login().expectStatus().isOk();
        }
    }

    // ---------- 超過上限 ----------

    @Test
    void loginWithinLimit_isForwarded() {
        exhaustLimit();

        USER_SERVICE.verify(LIMIT, postRequestedFor(urlEqualTo(LOGIN_PATH)));
    }

    @Test
    void loginOverLimit_returns429ProblemDetailWithoutCallingUserService() {
        exhaustLimit();

        login().expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                // requested-tokens / replenish-rate = 60 / 1：60 秒後才有足夠的 token
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS");

        USER_SERVICE.verify(LIMIT, postRequestedFor(urlEqualTo(LOGIN_PATH)));
    }

    @Test
    void forgedXForwardedFor_isIgnoredWhenNoProxyIsTrusted() {
        // trusted-proxies = 0：X-Forwarded-For 是用戶端自己填的，換來換去也不會換到新的額度
        for (int i = 0; i < LIMIT; i++) {
            webTestClient.post().uri(LOGIN_PATH)
                    .header("X-Forwarded-For", "203.0.113." + i)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isOk();
        }

        webTestClient.post().uri(LOGIN_PATH)
                .header("X-Forwarded-For", "203.0.113.99")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void redisKey_containsRouteIdAndClientIp() {
        login().expectStatus().isOk();

        // RedisRateLimiter 的 key 格式是 request_rate_limiter.{routeId.key}.tokens，不同路由的計數不會混在一起
        assertThat(redisTemplate.keys("request_rate_limiter.*.tokens").collectList().block())
                .containsExactly("request_rate_limiter.{user-service-login.127.0.0.1}.tokens");
    }

    // ---------- 不受影響的路由 ----------

    @Test
    void otherRoutes_areNotLimited() {
        exhaustLimit();
        login().expectStatus().isEqualTo(429);

        // 其他 auth API、公開的 GET、需要登入的 API 都照常
        webTestClient.post().uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
        webTestClient.post().uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/v1/products").exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .exchange().expectStatus().isOk();
    }

    @Test
    void nonPostLogin_isNotLimited() {
        // 只限制 POST；其他 method 走一般的 user-service 路由（user-service 會回 405）
        exhaustLimit();

        webTestClient.get().uri(LOGIN_PATH).exchange().expectStatus().isOk();
    }
}

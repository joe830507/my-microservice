package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

// 登入限流（Gateway 在一層代理後面，例如 K8s Ingress：trusted-proxies = 1）
// 來源 IP 取 X-Forwarded-For 最後一個值（代理加上的）；用戶端自己填在前面的值不影響
// 測試裡沒有真的代理，直接在 X-Forwarded-For 最後放「代理看到的 IP」
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.client-ip.trusted-proxies=1",
        "gateway.login-rate-limit.replenish-rate=1",
        "gateway.login-rate-limit.burst-capacity=180",
        "gateway.login-rate-limit.requested-tokens=60" })
@AutoConfigureWebTestClient
class GatewayLoginRateLimitBehindProxyIntegrationTests {

    private static final int LIMIT = 3;
    private static final String CLIENT_A = "198.51.100.1";
    private static final String CLIENT_B = "198.51.100.2";

    @RegisterExtension
    static final WireMockExtension PRODUCT_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension USER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.product-uri", PRODUCT_SERVICE::baseUrl);
        registry.add("gateway.services.user-uri", USER_SERVICE::baseUrl);
        TestRedis.register(registry);
    }

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        flushRedis();
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    @AfterEach
    void flushRedis() {
        redisTemplate.execute(connection -> connection.serverCommands().flushAll()).blockLast();
    }

    private WebTestClient.ResponseSpec login(String xForwardedFor) {
        return webTestClient.post().uri("/api/v1/auth/login")
                .header("X-Forwarded-For", xForwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange();
    }

    @Test
    void differentClientIps_haveSeparateLimits() {
        for (int i = 0; i < LIMIT; i++) {
            login(CLIENT_A).expectStatus().isOk();
        }
        login(CLIENT_A).expectStatus().isEqualTo(429);

        // 同一時間另一個 IP 不受影響
        login(CLIENT_B).expectStatus().isOk();
    }

    @Test
    void forgedLeadingXForwardedForValues_doNotBypassLimit() {
        // 用戶端自己帶 X-Forwarded-For，代理會把真正的 IP 加在最後；每次換前面的值也不會換到新的額度
        for (int i = 0; i < LIMIT; i++) {
            login("203.0.113." + i + ", " + CLIENT_A).expectStatus().isOk();
        }
        login("203.0.113.99, " + CLIENT_A).expectStatus().isEqualTo(429);
    }
}

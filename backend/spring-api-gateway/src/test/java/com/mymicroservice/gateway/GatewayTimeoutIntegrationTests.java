package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

// 逾時：JWKS（user-service）或下游服務卡住時，Gateway 要在時限內回應，不能讓連線一直被占著
// 測試把時限調短（500ms），WireMock 延遲 3 秒回應
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.security.jwt.jwks-response-timeout=500ms",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=500ms" })
@AutoConfigureWebTestClient
class GatewayTimeoutIntegrationTests {

    private static final int SLOW_MILLIS = 3000;
    // 比 SLOW_MILLIS 短很多：確認是 Gateway 自己的時限生效，不是等到下游回應
    private static final Duration MAX_ELAPSED = Duration.ofMillis(2000);

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

    @BeforeEach
    void stubServers() {
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    private WebTestClient slowClient() {
        // WebTestClient 預設 5 秒逾時；放寬，避免測試端先逾時而看不到 Gateway 的回應
        return webTestClient.mutate().responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void slowJwks_returns503WithinTimeout() {
        // 這個 context 還沒抓過 JWKS（快取是空的），一定要向 user-service 抓
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withFixedDelay(SLOW_MILLIS)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(TestJwts.jwks(TestJwts.SIGNING_KEY))));

        long start = System.nanoTime();
        slowClient().get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .exchange()
                // token 本身可能是有效的，只是現在無法驗證：回 503 而不是 401，用戶端不應因此登出
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(MAX_ELAPSED);
        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void unreachableJwks_returns503() {
        // 這個類別的測試都不會成功抓到 JWKS，快取一直是空的
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        webTestClient.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");

        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void slowDownstream_returns504WithinTimeout() {
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(SLOW_MILLIS)));

        long start = System.nanoTime();
        slowClient().get().uri("/api/v1/products").exchange()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(MAX_ELAPSED);
    }
}

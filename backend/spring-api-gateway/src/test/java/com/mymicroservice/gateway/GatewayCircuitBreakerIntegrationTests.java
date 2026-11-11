package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

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

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

// 每個路由的熔斷（階段 B3）：下游連不上、逾時才算失敗；4 次失敗就打開，之後不再轉送，直接回 503
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.resilience.sliding-window-size=4",
        "gateway.resilience.minimum-number-of-calls=4",
        "gateway.resilience.failure-rate-threshold=50",
        "gateway.resilience.wait-duration-in-open-state=1m",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=500ms" })
@AutoConfigureWebTestClient
class GatewayCircuitBreakerIntegrationTests {

    private static final String PRODUCT_PATH = "/api/v1/products/some-id";
    private static final String CATEGORY_PATH = "/api/v1/categories/tree";

    @RegisterExtension
    static final WireMockExtension PRODUCT_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension INVENTORY_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension JWKS_SERVER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.product-uri", PRODUCT_SERVICE::baseUrl);
        registry.add("gateway.services.inventory-uri", INVENTORY_SERVICE::baseUrl);
        registry.add("gateway.security.jwt.jwk-set-uri", () -> JWKS_SERVER.baseUrl() + "/jwks");
        TestRedis.register(registry);
    }

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void setUp() {
        PRODUCT_SERVICE.resetAll();
        INVENTORY_SERVICE.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(TestJwts.jwks(TestJwts.SIGNING_KEY))));
        INVENTORY_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("inventory-ok")));
    }

    private WebTestClient.ResponseSpec getProduct() {
        return webTestClient.get().uri(PRODUCT_PATH).exchange();
    }

    @Test
    void downstreamErrorStatus_isForwardedAsIsAndNeverOpensTheBreaker() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo(PRODUCT_PATH)).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":503,\"code\":\"PRODUCT_SERVICE_UNAVAILABLE\"}")));

        for (int i = 0; i < 8; i++) {
            getProduct().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectBody().jsonPath("$.code").isEqualTo("PRODUCT_SERVICE_UNAVAILABLE");
        }

        assertThat(circuitBreakers.circuitBreaker("product-service").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void connectionFailures_openTheBreaker_thenRequestsAreRejectedWithoutForwarding() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo(PRODUCT_PATH)).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        for (int i = 0; i < 4; i++) {
            getProduct().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
        }
        assertThat(circuitBreakers.circuitBreaker("product-service").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        int forwarded = PRODUCT_SERVICE.countRequestsMatching(getRequestedFor(urlEqualTo(PRODUCT_PATH)).build())
                .getCount();
        assertThat(forwarded).isEqualTo(4);

        // 打開之後：回 503，下游完全沒有收到新請求
        getProduct().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
        PRODUCT_SERVICE.verify(forwarded, getRequestedFor(urlEqualTo(PRODUCT_PATH)));
    }

    @Test
    void openBreakerOfOneService_doesNotAffectAnother() {
        circuitBreakers.circuitBreaker("product-service").transitionToOpenState();

        getProduct().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        webTestClient.get().uri("/api/v1/inventory/SKU-1").exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo("inventory-ok");
    }

    @Test
    void sameProductBreakerIsSharedAcrossProductAndCategoryPaths() {
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        getProduct().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        getProduct().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        webTestClient.get().uri(CATEGORY_PATH).exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        webTestClient.get().uri(CATEGORY_PATH).exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(circuitBreakers.circuitBreaker("product-service").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void slowDownstream_returns504WithTheSameErrorFormat() {
        PRODUCT_SERVICE.stubFor(get(urlEqualTo(PRODUCT_PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(2000)));

        getProduct().expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT");
    }
}

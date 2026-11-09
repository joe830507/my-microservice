package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

// /internal/** 從外部一律 404，下游完全收不到請求；包含用 .. 繞過路由比對、再由下游正規化成 /internal 的寫法
// 路徑都用 URI 物件傳：傳字串的話 WebTestClient 會再編碼一次（% 變成 %25），測不到原本的寫法
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayInternalPathIntegrationTests {

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
    @LocalServerPort
    private int port;

    @BeforeEach
    void stubServers() {
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(TestJwts.jwks(TestJwts.SIGNING_KEY))));
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    private URI uri(String rawPath) {
        return URI.create("http://localhost:" + port + rawPath);
    }

    private static void expectNoDownstreamCalls() {
        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 直接呼叫
            "/internal",
            "/internal/inventory/reserve",
            "/INTERNAL/inventory/reserve",
            "/internal;foo=bar/inventory/reserve",
            "/%69nternal/inventory/reserve",
            // 先符合 products 的路由，再由下游正規化成 /internal
            "/api/v1/products/../../../internal/inventory/reserve",
            "/api/v1/products/%2e%2e/%2e%2e/%2e%2e/internal/inventory/reserve",
            "/api/v1/products/%2E%2E/%2E%2E/%2E%2E/internal/inventory/reserve",
            "/api/v1/products/%252e%252e/%252e%252e/%252e%252e/internal/inventory/reserve",
            "/api/v1/products/..%2f..%2f..%2finternal/inventory/reserve",
            "/api/v1/products/..%5c..%5c..%5cinternal/inventory/reserve",
            // Tomcat 會去掉 ;參數，..; 等同 ..
            "/api/v1/products/..;/..;/..;/internal/inventory/reserve",
            // 不是 /internal，但含 . 或 .. 的路徑一律擋下
            "/api/v1/products/../categories",
            "/api/v1/products/./sku/SKU-001",
    })
    void blockedPaths_return404WithoutCallingDownstream(String rawPath) {
        webTestClient.get().uri(uri(rawPath)).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");

        expectNoDownstreamCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/internal/inventory/reserve",
            "/api/v1/users/../../../internal/inventory/reserve" })
    void blockedPaths_return404EvenWithValidToken(String rawPath) {
        // 登入的使用者也不能呼叫；而且回 404 不是 401，看不出路徑是否存在
        webTestClient.post().uri(uri(rawPath))
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNotFound();

        expectNoDownstreamCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // internal 不在開頭、. 只是片段裡的字元，都是正常路徑
            // （%25 這類編碼的 % 會被 Spring Security 的 StrictServerWebExchangeFirewall 擋下回 400，不在這裡測）
            "/api/v1/products/sku/internal",
            "/api/v1/products/sku/SKU.001",
            "/api/v1/products/sku/..SKU",
            "/api/v1/categories/internal-tools",
    })
    void normalPaths_areNotBlocked(String rawPath) {
        webTestClient.get().uri(uri(rawPath)).exchange()
                .expectStatus().isOk();

        PRODUCT_SERVICE.verify(1, anyRequestedFor(anyUrl()));
    }
}

package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.nimbusds.jose.jwk.RSAKey;

// JWT 驗簽：公開路由不需要 token；其餘路由沒有有效的 access token 一律 401，而且不會轉給下游
// JWKS 由獨立的 WireMock 提供，才不會干擾「下游有沒有收到請求」的檢查
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayJwtIntegrationTests {

    private static final String PROTECTED_PATH = "/api/v1/users/me";

    @RegisterExtension
    static final WireMockExtension PRODUCT_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension USER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension INVENTORY_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension ORDER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension PAYMENT_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension JWKS_SERVER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreamUris(DynamicPropertyRegistry registry) {
        registry.add("gateway.services.product-uri", PRODUCT_SERVICE::baseUrl);
        registry.add("gateway.services.user-uri", USER_SERVICE::baseUrl);
        registry.add("gateway.services.inventory-uri", INVENTORY_SERVICE::baseUrl);
        registry.add("gateway.services.order-uri", ORDER_SERVICE::baseUrl);
        registry.add("gateway.services.payment-uri", PAYMENT_SERVICE::baseUrl);
        registry.add("gateway.security.jwt.jwk-set-uri", () -> JWKS_SERVER.baseUrl() + "/jwks");
        TestRedis.register(registry);
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void stubServers() {
        stubJwks(TestJwts.SIGNING_KEY);
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        INVENTORY_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        ORDER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        PAYMENT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    private static void stubJwks(RSAKey... keys) {
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(TestJwts.jwks(keys))));
    }

    private WebTestClient.ResponseSpec getWithToken(String path, String token) {
        return webTestClient.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private static void expectUnauthenticated(WebTestClient.ResponseSpec response) {
        response.expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value -> value.startsWith("Bearer"))
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
    }

    private static void expectNoDownstreamCalls() {
        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        PAYMENT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------- 有效的 token ----------

    @Test
    void validToken_isForwardedToDownstream() {
        String token = TestJwts.validToken();

        getWithToken(PROTECTED_PATH, token).expectStatus().isOk();

        USER_SERVICE.verify(getRequestedFor(urlEqualTo(PROTECTED_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer " + token)));
    }

    @Test
    void tokenSignedWithRotatedKey_isAcceptedAfterJwksIsUpdated() {
        // user-service 換了金鑰：Gateway 快取的 JWKS 還沒有新的 kid，會重新抓一次
        RSAKey newKey = TestJwts.generate("test-key-rotated");
        String token = TestJwts.token(newKey, claims -> { });

        expectUnauthenticated(getWithToken(PROTECTED_PATH, token));

        stubJwks(newKey, TestJwts.SIGNING_KEY);
        getWithToken(PROTECTED_PATH, token).expectStatus().isOk();
        // 舊金鑰簽的 token 在輪替期間仍然有效
        getWithToken(PROTECTED_PATH, TestJwts.validToken()).expectStatus().isOk();
    }

    // ---------- 沒有有效的 token ----------

    @Test
    void missingToken_returns401() {
        expectUnauthenticated(webTestClient.get().uri(PROTECTED_PATH).exchange());
        expectNoDownstreamCalls();
    }

    @Test
    void nonBearerScheme_returns401() {
        expectUnauthenticated(webTestClient.get().uri(PROTECTED_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Basic YWxpY2U6cGFzc3dvcmQ=")
                .exchange());
        expectNoDownstreamCalls();
    }

    @Test
    void malformedToken_returns401() {
        expectUnauthenticated(getWithToken(PROTECTED_PATH, "not-a-jwt"));
        expectNoDownstreamCalls();
    }

    @Test
    void wrongSignature_returns401() {
        // kid 相同但私鑰不同：例如有人自己產生金鑰對、冒用 kid
        RSAKey forgedKey = TestJwts.generate(TestJwts.SIGNING_KEY.getKeyID());

        expectUnauthenticated(getWithToken(PROTECTED_PATH, TestJwts.token(forgedKey, claims -> { })));
        expectNoDownstreamCalls();
    }

    @Test
    void unknownKid_returns401() {
        RSAKey unknownKey = TestJwts.generate("test-key-unknown");

        expectUnauthenticated(getWithToken(PROTECTED_PATH, TestJwts.token(unknownKey, claims -> { })));
        expectNoDownstreamCalls();
    }

    @Test
    void expiredToken_returns401() {
        expectUnauthenticated(getWithToken(PROTECTED_PATH, TestJwts.expiredToken()));
        expectNoDownstreamCalls();
    }

    @Test
    void wrongIssuer_returns401() {
        String token = TestJwts.token(TestJwts.SIGNING_KEY, claims -> claims.issuer("someone-else"));

        expectUnauthenticated(getWithToken(PROTECTED_PATH, token));
        expectNoDownstreamCalls();
    }

    @Test
    void missingIssuer_returns401() {
        String token = TestJwts.token(TestJwts.SIGNING_KEY, claims -> claims.issuer(null));

        expectUnauthenticated(getWithToken(PROTECTED_PATH, token));
        expectNoDownstreamCalls();
    }

    @Test
    void missingSubject_returns401() {
        // sub 是 userId，下一步要轉成 X-User-Id
        String token = TestJwts.token(TestJwts.SIGNING_KEY, claims -> claims.subject(null));

        expectUnauthenticated(getWithToken(PROTECTED_PATH, token));
        expectNoDownstreamCalls();
    }

    @Test
    void writeToProducts_requiresToken() {
        // 商品只有讀取公開
        expectUnauthenticated(webTestClient.post().uri("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange());
        expectNoDownstreamCalls();
    }

    @Test
    void writeToInventory_requiresToken() {
        // 庫存只有讀取公開；設定庫存要登入（ADMIN 由 inventory-service 檢查）
        expectUnauthenticated(webTestClient.put().uri("/api/v1/inventory/SKU-001")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"available\": 10}")
                .exchange());
        expectNoDownstreamCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/orders", "/api/v1/orders/01a1bfeb-4351-7899-9b58-142decdf484e" })
    void orderRoutes_requireToken(String path) {
        // 訂單沒有公開的路由，GET 也要登入
        expectUnauthenticated(webTestClient.get().uri(path).exchange());
        expectUnauthenticated(webTestClient.post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange());
        expectNoDownstreamCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/payments", "/api/v1/payments/01a1bfeb-4351-7899-9b58-142decdf4850/mock-callback" })
    void paymentRoutes_requireToken(String path) {
        expectUnauthenticated(webTestClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange());
        expectUnauthenticated(webTestClient.get().uri("/api/v1/payments/01a1bfeb-4351-7899-9b58-142decdf4850").exchange());
        expectNoDownstreamCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/shipments", "/api/v1/unknown" })
    void unroutedPathWithoutToken_returns401(String path) {
        // 沒登入看不出哪些路徑存在；有 token 才會拿到 404（見 GatewayRoutingIntegrationTests）
        expectUnauthenticated(webTestClient.get().uri(path).exchange());
    }

    @Test
    void invalidTokenOnPublicGetRoute_returns401() {
        // 帶了 token 就會驗證；用戶端 refresh 後重試即可
        expectUnauthenticated(getWithToken("/api/v1/products", TestJwts.expiredToken()));
        expectNoDownstreamCalls();
    }

    // ---------- 公開路由 ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/products", "/api/v1/products/sku/SKU-001", "/api/v1/categories/tree",
            "/api/v1/auth/jwks", "/api/v1/inventory/SKU-001" })
    void publicGetRoutes_doNotRequireToken(String path) {
        webTestClient.get().uri(path).exchange().expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh",
            "/api/v1/auth/logout" })
    void authRoutes_doNotRequireToken(String path) {
        webTestClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void authRoutes_ignoreExpiredToken() {
        // 用戶端在 access token 過期後呼叫 refresh，常常還帶著舊的 Authorization
        webTestClient.post().uri("/api/v1/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.expiredToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void health_doesNotRequireToken() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}

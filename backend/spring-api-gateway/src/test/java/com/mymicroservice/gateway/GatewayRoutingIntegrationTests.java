package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

// 路由：依路徑轉到正確的下游服務，method、query、header、body 原樣轉送，回應原樣傳回
// 下游服務用 WireMock 模擬，每個服務一個隨機 port；需要登入的路由帶有效的 token（JWT 驗證見 GatewayJwtIntegrationTests）
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingIntegrationTests {

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

    // JWKS 獨立一個 server，才不會讓 user-service 的請求紀錄多出 JWKS 的呼叫
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
    @LocalServerPort
    private int port;

    @BeforeEach
    void stubDownstreams() {
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(TestJwts.jwks(TestJwts.SIGNING_KEY))));
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"from\": \"product-service\"}")));
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"from\": \"user-service\"}")));
        INVENTORY_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"from\": \"inventory-service\"}")));
        ORDER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"from\": \"order-service\"}")));
        PAYMENT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"from\": \"payment-service\"}")));
    }

    // ---------- product-service ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/products", "/api/v1/products/sku/SKU-001", "/api/v1/categories",
            "/api/v1/categories/tree" })
    void productPaths_areRoutedToProductService(String path) {
        webTestClient.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("product-service");

        PRODUCT_SERVICE.verify(getRequestedFor(urlEqualTo(path)));
        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void queryString_isForwardedUnchanged() {
        // 傳完整的 URI 物件：傳字串的話 WebTestClient 會把 % 當成一般字元再編碼一次（變成 %25）
        // 這裡要驗的是 Gateway 不會重複編碼已編碼的 query（中文關鍵字）
        URI uri = URI.create("http://localhost:" + port + "/api/v1/products?page=1&size=20&keyword=%E9%9E%8B");
        webTestClient.get().uri(uri).exchange()
                .expectStatus().isOk();

        PRODUCT_SERVICE.verify(getRequestedFor(urlEqualTo("/api/v1/products?page=1&size=20&keyword=%E9%9E%8B")));
    }

    // ---------- user-service ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/auth/jwks", "/api/v1/users/me", "/api/v1/users" })
    void userPaths_areRoutedToUserService(String path) {
        webTestClient.get().uri(path).headers(headers -> headers.setBearerAuth(TestJwts.validToken())).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("user-service");

        USER_SERVICE.verify(getRequestedFor(urlEqualTo(path)));
        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void postBodyAndContentType_areForwardedUnchanged() {
        String body = "{\"email\": \"alice@example.com\", \"password\": \"password123\"}";

        webTestClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        USER_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/auth/login"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson(body)));
    }

    // ---------- inventory-service ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/inventory/SKU-001", "/api/v1/inventory" })
    void inventoryPaths_areRoutedToInventoryService(String path) {
        webTestClient.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("inventory-service");

        INVENTORY_SERVICE.verify(getRequestedFor(urlEqualTo(path)));
        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void inventoryPut_isForwardedWithBody() {
        String body = "{\"available\": 10}";

        webTestClient.put().uri("/api/v1/inventory/SKU-001")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        INVENTORY_SERVICE.verify(putRequestedFor(urlEqualTo("/api/v1/inventory/SKU-001"))
                .withRequestBody(equalToJson(body)));
    }

    // ---------- order-service ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/orders", "/api/v1/orders/01a1bfeb-4351-7899-9b58-142decdf484e" })
    void orderPaths_areRoutedToOrderService(String path) {
        webTestClient.get().uri(path).headers(headers -> headers.setBearerAuth(TestJwts.validToken())).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("order-service");

        ORDER_SERVICE.verify(getRequestedFor(urlEqualTo(path)));
        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void orderCreate_isForwardedWithBody() {
        String body = "{\"items\": [{\"productId\": \"01a1bfeb-4351-7899-9b58-142decdf484e\", \"qty\": 1}]}";

        webTestClient.post().uri("/api/v1/orders")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk();

        ORDER_SERVICE.verify(postRequestedFor(urlEqualTo("/api/v1/orders")).withRequestBody(equalToJson(body)));
    }

    // ---------- payment-service ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/payments", "/api/v1/payments/%s/mock-callback" })
    void paymentPaths_areRoutedToPaymentService(String template) {
        String path = template.formatted("01a1bfeb-4351-7899-9b58-142decdf4850");
        webTestClient.post().uri(path)
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.from").isEqualTo("payment-service");

        PAYMENT_SERVICE.verify(postRequestedFor(urlEqualTo(path)));
        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------- 回應 ----------

    @Test
    void downstreamErrorResponse_isPassedThroughUnchanged() {
        USER_SERVICE.stubFor(any(urlEqualTo("/api/v1/users/me")).willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withBody("{\"status\": 404, \"code\": \"USER_NOT_FOUND\"}")));

        webTestClient.get().uri("/api/v1/users/me").headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void downstreamConnectionFailure_returns5xx() {
        // 下游服務掛掉或連線中斷：Gateway 回 5xx，不會卡住或回 200
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        webTestClient.get().uri("/api/v1/products").exchange()
                .expectStatus().is5xxServerError();
    }

    // ---------- 不在路由表 ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/shipments", "/api/v1/unknown", "/api/v2/products", "/products" })
    void unroutedPaths_return404WithoutCallingAnyService(String path) {
        // 帶有效的 token：沒帶的話先被 JWT 驗證擋下回 401
        webTestClient.get().uri(path).headers(headers -> headers.setBearerAuth(TestJwts.validToken())).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);

        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        USER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        PAYMENT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void health_isUp() {
        webTestClient.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}

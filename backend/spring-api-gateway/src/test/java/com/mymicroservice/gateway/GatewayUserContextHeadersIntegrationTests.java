package com.mymicroservice.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

// 身分 header：用戶端送來的 X-User-Id、X-User-Roles 一律移除；有有效的 JWT 才由 Gateway 依 sub、roles 填入
// 下游服務信任這兩個 header，用戶端偽造的值絕不能傳到下游
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayUserContextHeadersIntegrationTests {

    private static final String FORGED_USER_ID = "00000000-0000-0000-0000-000000000001";

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
        JWKS_SERVER.stubFor(get(urlEqualTo("/jwks")).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(TestJwts.jwks(TestJwts.SIGNING_KEY))));
        PRODUCT_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        USER_SERVICE.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
    }

    // 下游收到的唯一一筆請求
    private static LoggedRequest singleRequest(WireMockExtension service) {
        List<LoggedRequest> requests = service.findAll(anyRequestedFor(anyUrl()));
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    // ---------- 有有效的 JWT ----------

    @Test
    void validToken_addsUserIdAndRolesFromJwt() {
        String token = TestJwts.token(TestJwts.SIGNING_KEY, claims -> claims.claim("roles", List.of("ADMIN", "CUSTOMER")));

        webTestClient.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk();

        LoggedRequest request = singleRequest(USER_SERVICE);
        assertThat(request.getHeaders().getHeader("X-User-Id").values()).containsExactly(TestJwts.USER_ID);
        assertThat(request.getHeaders().getHeader("X-User-Roles").values()).containsExactly("ADMIN,CUSTOMER");
    }

    @Test
    void validToken_replacesForgedHeaders() {
        webTestClient.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .header("X-User-Id", FORGED_USER_ID)
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        LoggedRequest request = singleRequest(USER_SERVICE);
        assertThat(request.getHeaders().getHeader("X-User-Id").values()).containsExactly(TestJwts.USER_ID);
        assertThat(request.getHeaders().getHeader("X-User-Roles").values()).containsExactly("CUSTOMER");
    }

    @Test
    void validToken_replacesForgedHeadersRegardlessOfCaseAndRepetition() {
        // HTTP header 名稱不分大小寫；同名送多筆時，下游可能只讀第一筆
        webTestClient.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .header("x-user-id", FORGED_USER_ID)
                .header("X-USER-ID", FORGED_USER_ID)
                .header("x-user-roles", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        LoggedRequest request = singleRequest(USER_SERVICE);
        assertThat(request.getHeaders().getHeader("X-User-Id").values()).containsExactly(TestJwts.USER_ID);
        assertThat(request.getHeaders().getHeader("X-User-Roles").values()).containsExactly("CUSTOMER");
    }

    @Test
    void validTokenWithoutRoles_sendsNoRolesHeader() {
        webTestClient.get().uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(TestJwts.token(TestJwts.SIGNING_KEY,
                        claims -> claims.claim("roles", null))))
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        USER_SERVICE.verify(anyRequestedFor(anyUrl())
                .withHeader("X-User-Id", equalTo(TestJwts.USER_ID))
                .withHeader("X-User-Roles", absent()));
    }

    @Test
    void validTokenOnPublicGetRoute_addsIdentity() {
        // 公開路由帶了有效 token 也填入身分，下游可依登入者調整回應
        webTestClient.get().uri("/api/v1/products")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .header("X-User-Id", FORGED_USER_ID)
                .exchange()
                .expectStatus().isOk();

        LoggedRequest request = singleRequest(PRODUCT_SERVICE);
        assertThat(request.getHeaders().getHeader("X-User-Id").values()).containsExactly(TestJwts.USER_ID);
    }

    // ---------- 沒有 JWT（公開路由） ----------

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh" })
    void authRoutes_removeForgedHeaders(String path) {
        // 否則用戶端可以在註冊、登入時偽造身分，讓稽核欄位記錯人
        webTestClient.post().uri(path)
                .header("X-User-Id", FORGED_USER_ID)
                .header("x-user-roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        USER_SERVICE.verify(anyRequestedFor(urlEqualTo(path))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Roles", absent()));
    }

    @Test
    void authRoutesWithValidToken_stillDoNotAddIdentity() {
        // /api/v1/auth/** 不讀 Authorization，身分只來自 refresh token 或帳密
        webTestClient.post().uri("/api/v1/auth/logout")
                .headers(headers -> headers.setBearerAuth(TestJwts.validToken()))
                .header("X-User-Id", FORGED_USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        USER_SERVICE.verify(anyRequestedFor(urlEqualTo("/api/v1/auth/logout"))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Roles", absent()));
    }

    @Test
    void publicGetRouteWithoutToken_removesForgedHeaders() {
        webTestClient.get().uri("/api/v1/products")
                .header("X-User-Id", FORGED_USER_ID)
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk();

        PRODUCT_SERVICE.verify(anyRequestedFor(urlEqualTo("/api/v1/products"))
                .withHeader("X-User-Id", absent())
                .withHeader("X-User-Roles", absent()));
    }

    @Test
    void forgedHeadersWithoutToken_onProtectedRoute_areRejected() {
        // 只帶偽造的 header、沒有 token：在 JWT 驗證就被擋下，不會轉給下游
        webTestClient.post().uri("/api/v1/products")
                .header("X-User-Id", FORGED_USER_ID)
                .header("X-User-Roles", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();

        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }
}

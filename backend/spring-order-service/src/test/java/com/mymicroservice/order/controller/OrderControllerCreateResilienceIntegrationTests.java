package com.mymicroservice.order.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mymicroservice.common.resilience.DownstreamCallers;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.order.TestcontainersConfiguration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

// 建單對 product、inventory 的重試與熔斷（階段 B1）：4 次記錄到的失敗就打開熔斷器，每次呼叫最多嘗試 3 次，重試前只等 1ms
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerCreateResilienceIntegrationTests {
    private static final String ORDERS_URL = "/api/v1/orders";
    private static final String BATCH_PATH = "/api/v1/products/batch";
    private static final String RESERVATIONS_PATH = "/internal/v1/inventory/reservations";

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SHOE_ID = UUID.randomUUID();

    @RegisterExtension
    static final WireMockExtension PRODUCT_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension INVENTORY_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("order.services.product-uri", PRODUCT_SERVICE::baseUrl);
        registry.add("order.services.inventory-uri", INVENTORY_SERVICE::baseUrl);
        registry.add("order.services.resilience.sliding-window-size", () -> "4");
        registry.add("order.services.resilience.minimum-number-of-calls", () -> "4");
        registry.add("order.services.resilience.failure-rate-threshold", () -> "50");
        registry.add("order.services.resilience.wait-duration-in-open-state", () -> "1m");
        registry.add("order.services.resilience.retry-max-attempts", () -> "3");
        registry.add("order.services.resilience.retry-initial-backoff", () -> "1ms");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DownstreamCallers callers;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
        PRODUCT_SERVICE.resetAll();
        INVENTORY_SERVICE.resetAll();
        // 熔斷器的狀態在整個 context 內共用：每個測試從關閉開始
        callers.circuitBreakers().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        stubProducts(200);
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse().withStatus(201)));
        INVENTORY_SERVICE.stubFor(WireMock.post(urlPathMatching(RESERVATIONS_PATH + "/.+/(release|confirm)"))
                .willReturn(aResponse().withStatus(200)));
    }

    // ---------- product ----------

    @Test
    void productFailsOnceThenRecovers_isRetriedAndTheOrderIsCreated() throws Exception {
        PRODUCT_SERVICE.resetAll();
        stubProductsFlaky();

        create().andExpect(status().isCreated());

        PRODUCT_SERVICE.verify(2, getRequestedFor(urlPathEqualTo(BATCH_PATH)));
    }

    @Test
    void productAlwaysDown_retriesThenOpensTheBreakerAndStopsCallingIt() throws Exception {
        stubProducts(503);

        // 第一次：3 次嘗試都失敗；第二次：第 4 次失敗後熔斷器打開，後面的嘗試直接被拒絕
        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
        create().andExpect(status().isServiceUnavailable());
        int callsWhenOpened = PRODUCT_SERVICE.countRequestsMatching(getRequestedFor(urlPathEqualTo(BATCH_PATH)).build())
                .getCount();
        assertThat(callsWhenOpened).isEqualTo(4);
        assertThat(callers.circuitBreakers().circuitBreaker("product-service").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // 熔斷器打開之後：立即回 503，完全沒有再打 product-service
        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
        PRODUCT_SERVICE.verify(callsWhenOpened, getRequestedFor(urlPathEqualTo(BATCH_PATH)));
        INVENTORY_SERVICE.verify(0, postRequestedFor(urlEqualTo(RESERVATIONS_PATH)));
    }

    @Test
    void productReturns404Items_isNotAFailure() throws Exception {
        // 查不到商品是業務結果（回空清單），不能讓熔斷器累積失敗
        PRODUCT_SERVICE.resetAll();
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody("{\"products\": []}")));

        for (int i = 0; i < 6; i++) {
            create().andExpect(status().isNotFound());
        }

        assertThat(callers.circuitBreakers().circuitBreaker("product-service").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------- inventory ----------

    @Test
    void inventoryFailsOnceThenRecovers_reservationIsRetriedWithoutRelease() throws Exception {
        INVENTORY_SERVICE.resetAll();
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH))
                .inScenario("flaky").whenScenarioStateIs("up").willReturn(aResponse().withStatus(201)));

        create().andExpect(status().isCreated());

        INVENTORY_SERVICE.verify(2, postRequestedFor(urlEqualTo(RESERVATIONS_PATH)));
        INVENTORY_SERVICE.verify(0, postRequestedFor(urlPathMatching(RESERVATIONS_PATH + "/.+/release")));
    }

    @Test
    void inventoryAlwaysDown_opensTheBreaker_andLaterOrdersFailFastWithoutAnyInventoryCall() throws Exception {
        INVENTORY_SERVICE.resetAll();
        INVENTORY_SERVICE.stubFor(WireMock.post(urlPathMatching(RESERVATIONS_PATH + ".*"))
                .willReturn(aResponse().withStatus(503)));

        // 每張訂單：預扣 3 次嘗試都失敗 → 結果不明 → 釋放（也失敗，也算在熔斷器裡）→ 503
        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_SERVICE_UNAVAILABLE"));
        assertThat(callers.circuitBreakers().circuitBreaker("inventory-service").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhenOpened = INVENTORY_SERVICE.countRequestsMatching(
                postRequestedFor(urlPathMatching(RESERVATIONS_PATH + ".*")).build()).getCount();

        // 熔斷器打開之後：確定沒有預扣，所以也不必釋放；立即回 503，沒有再打 inventory-service
        create().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_SERVICE_UNAVAILABLE"));
        INVENTORY_SERVICE.verify(callsWhenOpened, postRequestedFor(urlPathMatching(RESERVATIONS_PATH + ".*")));
        assertThat(countOrders()).isZero();
    }

    @Test
    void outOfStock_isABusinessResultNotAFailure() throws Exception {
        INVENTORY_SERVICE.resetAll();
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":409,\"code\":\"INSUFFICIENT_STOCK\",\"detail\":\"insufficient stock: SHOE-001.\"}")));

        for (int i = 0; i < 6; i++) {
            create().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUT_OF_STOCK"));
        }

        // 沒有重試（每張訂單一次），熔斷器也沒有累積失敗
        INVENTORY_SERVICE.verify(6, postRequestedFor(urlEqualTo(RESERVATIONS_PATH)));
        assertThat(callers.circuitBreakers().circuitBreaker("inventory-service").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------- helpers ----------

    private void stubProducts(int status) {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).willReturn(status == 200
                ? aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody(
                        "{\"products\": [{\"id\": \"%s\", \"sku\": \"SHOE-001\", \"name\": \"Running Shoe\", \"price\": 199.99, \"status\": \"ACTIVE\"}]}"
                                .formatted(SHOE_ID))
                : aResponse().withStatus(status)));
    }

    private void stubProductsFlaky() {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("up"));
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).inScenario("flaky").whenScenarioStateIs("up")
                .willReturn(aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody(
                        "{\"products\": [{\"id\": \"%s\", \"sku\": \"SHOE-001\", \"name\": \"Running Shoe\", \"price\": 199.99, \"status\": \"ACTIVE\"}]}"
                                .formatted(SHOE_ID))));
    }

    private ResultActions create() throws Exception {
        return mockMvc.perform(post(ORDERS_URL)
                .header(UserContextHeaders.USER_ID, USER_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": \"%s\", \"qty\": 1}]}".formatted(SHOE_ID)));
    }

    private int countOrders() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.orders", Integer.class);
    }
}

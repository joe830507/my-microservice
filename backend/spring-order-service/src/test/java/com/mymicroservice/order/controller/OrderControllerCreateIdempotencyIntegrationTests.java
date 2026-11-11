package com.mymicroservice.order.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.order.TestcontainersConfiguration;

// POST /api/v1/orders 的 Idempotency-Key（SEC-10）
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerCreateIdempotencyIntegrationTests {
    private static final String ORDERS_URL = "/api/v1/orders";
    private static final String BATCH_PATH = "/api/v1/products/batch";
    private static final String RESERVATIONS_PATH = "/internal/v1/inventory/reservations";
    private static final String KEY = "checkout-0001-abcdef";

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID SHOE_ID = UUID.randomUUID();
    private static final UUID SOCK_ID = UUID.randomUUID();

    @RegisterExtension
    static final WireMockExtension PRODUCT_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static final WireMockExtension INVENTORY_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("order.services.product-uri", PRODUCT_SERVICE::baseUrl);
        registry.add("order.services.inventory-uri", INVENTORY_SERVICE::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.orders CASCADE");
        PRODUCT_SERVICE.resetAll();
        INVENTORY_SERVICE.resetAll();
        stubProducts(0);
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse().withStatus(201)));
        INVENTORY_SERVICE.stubFor(WireMock.post(WireMock.urlPathMatching(RESERVATIONS_PATH + "/.+/release"))
                .willReturn(aResponse().withStatus(204)));
    }

    @Test
    void sameKeySameBody_returnsTheSameOrderWithoutReservingAgain() throws Exception {
        MvcResult first = create(USER_ID, KEY, item(SHOE_ID, 2)).andExpect(status().isCreated()).andReturn();
        String orderId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        create(USER_ID, KEY, item(SHOE_ID, 2))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.items[0].qty").value(2));

        assertThat(countOrders()).isEqualTo(1);
        INVENTORY_SERVICE.verify(1, postRequestedFor(urlEqualTo(RESERVATIONS_PATH)));
        // 重送不再查商品
        PRODUCT_SERVICE.verify(1, getRequestedFor(urlPathEqualTo(BATCH_PATH)));
    }

    @Test
    void sameKey_itemOrderAndDuplicatesDoNotMatter() throws Exception {
        String orderId = JsonPath.read(create(USER_ID, KEY, item(SHOE_ID, 1), item(SOCK_ID, 1))
                .andReturn().getResponse().getContentAsString(), "$.id");

        // 品項順序不同、同商品拆成兩列，合併後內容相同
        create(USER_ID, KEY, item(SOCK_ID, 1), item(SHOE_ID, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_returns422() throws Exception {
        create(USER_ID, KEY, item(SHOE_ID, 2)).andExpect(status().isCreated());

        create(USER_ID, KEY, item(SHOE_ID, 3))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countOrders()).isEqualTo(1);
        INVENTORY_SERVICE.verify(1, postRequestedFor(urlEqualTo(RESERVATIONS_PATH)));
    }

    @Test
    void sameKeyFromAnotherUser_createsItsOwnOrder() throws Exception {
        String mine = JsonPath.read(create(USER_ID, KEY, item(SHOE_ID, 1))
                .andReturn().getResponse().getContentAsString(), "$.id");

        String theirs = JsonPath.read(create(OTHER_USER_ID, KEY, item(SHOE_ID, 1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(countOrders()).isEqualTo(2);
    }

    @Test
    void differentKeys_createDifferentOrders() throws Exception {
        create(USER_ID, KEY, item(SHOE_ID, 1)).andExpect(status().isCreated());
        create(USER_ID, "checkout-0002-abcdef", item(SHOE_ID, 1)).andExpect(status().isCreated());

        assertThat(countOrders()).isEqualTo(2);
    }

    @Test
    void noKey_behavesAsBefore() throws Exception {
        create(USER_ID, null, item(SHOE_ID, 1)).andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        create(USER_ID, null, item(SHOE_ID, 1)).andExpect(status().isCreated());

        assertThat(countOrders()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_service.orders WHERE idempotency_key IS NOT NULL", Integer.class)).isZero();
    }

    @Test
    void replayStillWorksAfterTheProductIsNoLongerAvailable() throws Exception {
        String orderId = JsonPath.read(create(USER_ID, KEY, item(SHOE_ID, 1))
                .andReturn().getResponse().getContentAsString(), "$.id");
        PRODUCT_SERVICE.resetAll();
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).willReturn(aResponse().withStatus(503)));

        create(USER_ID, KEY, item(SHOE_ID, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));
    }

    @ParameterizedTest
    @ValueSource(strings = { "short", "has space in it", "bad/char/here", "x!x!x!x!x!x!" })
    void malformedKey_returns400(String key) throws Exception {
        create(USER_ID, key, item(SHOE_ID, 1)).andExpect(status().isBadRequest());

        assertThat(countOrders()).isZero();
    }

    @Test
    void keyLongerThan64_returns400() throws Exception {
        create(USER_ID, "a".repeat(65), item(SHOE_ID, 1)).andExpect(status().isBadRequest());
        create(USER_ID, "a".repeat(64), item(SHOE_ID, 1)).andExpect(status().isCreated());
    }

    @Test
    void concurrentDuplicates_createOneOrderAndReleaseTheLoserReservation() throws Exception {
        // 商品查詢拖慢一下，讓兩個請求都先通過「有沒有建過」的檢查，真正撞在唯一約束上
        stubProducts(400);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> request = () -> create(USER_ID, KEY, item(SHOE_ID, 1)).andReturn();
            Future<MvcResult> a = pool.submit(request);
            Future<MvcResult> b = pool.submit(request);
            MvcResult resultA = a.get();
            MvcResult resultB = b.get();

            List<Integer> statuses = List.of(resultA.getResponse().getStatus(), resultB.getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(201, 200);
            String idA = JsonPath.read(resultA.getResponse().getContentAsString(), "$.id");
            String idB = JsonPath.read(resultB.getResponse().getContentAsString(), "$.id");
            assertThat(idA).isEqualTo(idB);
        } finally {
            pool.shutdownNow();
        }

        assertThat(countOrders()).isEqualTo(1);
        // 兩個請求都預扣過，輸的那個釋放自己的預扣
        INVENTORY_SERVICE.verify(2, postRequestedFor(urlEqualTo(RESERVATIONS_PATH)));
        INVENTORY_SERVICE.verify(1, postRequestedFor(WireMock.urlPathMatching(RESERVATIONS_PATH + "/.+/release")));
    }

    // ---------- helpers ----------

    private static void stubProducts(int delayMillis) {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).willReturn(aResponse()
                .withFixedDelay(delayMillis)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"products\": [" + product(SHOE_ID, "SHOE-001", "Running Shoe", "199.99") + ", "
                        + product(SOCK_ID, "SOCK-001", "Sock", "50.00") + "]}")));
    }

    private static String product(UUID id, String sku, String name, String price) {
        return "{\"id\": \"%s\", \"sku\": \"%s\", \"name\": \"%s\", \"price\": %s, \"status\": \"ACTIVE\"}"
                .formatted(id, sku, name, price);
    }

    private org.springframework.test.web.servlet.ResultActions create(UUID userId, String key, String... items)
            throws Exception {
        var request = post(ORDERS_URL)
                .header(UserContextHeaders.USER_ID, userId.toString())
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [" + String.join(", ", items) + "]}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private static String item(UUID productId, int qty) {
        return "{\"productId\": \"%s\", \"qty\": %d}".formatted(productId, qty);
    }

    private int countOrders() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.orders", Integer.class);
    }
}

package com.mymicroservice.order.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.ResultActions;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.order.TestcontainersConfiguration;

// POST /api/v1/orders：product-service、inventory-service 用 WireMock 模擬
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerCreateIntegrationTests {
    private static final String ORDERS_URL = "/api/v1/orders";
    private static final String BATCH_PATH = "/api/v1/products/batch";
    private static final String RESERVATIONS_PATH = "/internal/v1/inventory/reservations";

    private static final UUID USER_ID = UUID.randomUUID();
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
        // 逾時的測試不用等 5 秒
        registry.add("order.services.read-timeout", () -> "1s");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE order_service.outbox_event, order_service.orders CASCADE");
        stubProducts(product(SHOE_ID, "SHOE-001", "Running Shoe", "199.99", "ACTIVE"),
                product(SOCK_ID, "SOCK-001", "Sock", "50.00", "ACTIVE"));
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse().withStatus(201)));
    }

    // ---------- 成功 ----------

    @Test
    void createOrder_snapshotsProductsReservesStockAndSaves() throws Exception {
        String response = create(item(SHOE_ID, 2), item(SOCK_ID, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(449.98))
                // 依 SKU 排序
                .andExpect(jsonPath("$.items[0].sku").value("SHOE-001"))
                .andExpect(jsonPath("$.items[0].productId").value(SHOE_ID.toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Running Shoe"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(199.99))
                .andExpect(jsonPath("$.items[0].qty").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").value(399.98))
                .andExpect(jsonPath("$.items[1].sku").value("SOCK-001"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.paidAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(response, "$.id"));

        // id 是 UUIDv7
        assertThat(orderId.version()).isEqualTo(7);

        // 預扣用訂單 id 當冪等鍵，品項是 SKU 與數量
        INVENTORY_SERVICE.verify(postRequestedFor(urlEqualTo(RESERVATIONS_PATH))
                .withRequestBody(equalToJson("""
                        {"orderId": "%s", "items": [{"sku": "SHOE-001", "qty": 2}, {"sku": "SOCK-001", "qty": 1}]}
                        """.formatted(orderId))));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM order_service.orders WHERE id = ?", orderId);
        assertThat(row.get("user_id")).isEqualTo(USER_ID);
        assertThat(row.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(row.get("reservation_status")).isEqualTo("RESERVED");

        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("449.98");
        assertThat(row.get("created_by")).isEqualTo(USER_ID);
        assertThat(countItems(orderId)).isEqualTo(2);
        assertThat(countEvent("OrderCreated", orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT payload->'payload'->>'userId' FROM order_service.outbox_event "
                + "WHERE event_type = 'OrderCreated' AND aggregate_id = ?", String.class, orderId.toString()))
                .isEqualTo(USER_ID.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT (payload->'payload'->>'totalAmount')::numeric "
                + "FROM order_service.outbox_event WHERE event_type = 'OrderCreated' AND aggregate_id = ?",
                BigDecimal.class, orderId.toString())).isEqualByComparingTo("449.98");
    }

    @Test
    void createOrder_responseHasLocationHeader() throws Exception {
        var result = create(item(SHOE_ID, 1))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        String orderId = JsonPath.read(result.getContentAsString(), "$.id");

        assertThat(result.getHeader("Location")).isEqualTo("/api/v1/orders/" + orderId);
    }

    @Test
    void createOrder_duplicateProducts_areMerged() throws Exception {
        create(item(SHOE_ID, 1), item(SHOE_ID, 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].qty").value(3))
                .andExpect(jsonPath("$.totalAmount").value(599.97));

        PRODUCT_SERVICE.verify(getRequestedFor(urlPathEqualTo(BATCH_PATH))
                .withQueryParam("ids", equalTo(SHOE_ID.toString())));
    }

    @Test
    void createOrder_eachOrderGetsNewId() throws Exception {
        String first = create(item(SHOE_ID, 1)).andReturn().getResponse().getContentAsString();
        String second = create(item(SHOE_ID, 1)).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(first, "$.id")).isNotEqualTo(JsonPath.read(second, "$.id"));
        assertThat(countOrders()).isEqualTo(2);
    }

    // ---------- 商品 ----------

    @Test
    void createOrder_unknownProduct_returns404WithoutReserving() throws Exception {
        UUID unknown = UUID.randomUUID();

        create(item(SHOE_ID, 1), item(unknown, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail", containsString(unknown.toString())));

        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        assertThat(countOrders()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event "
                + "WHERE event_type = 'OrderCreated'", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "DRAFT", "INACTIVE", "ARCHIVED" })
    void createOrder_productNotActive_returns409WithoutReserving(String status) throws Exception {
        stubProducts(product(SHOE_ID, "SHOE-001", "Running Shoe", "199.99", "ACTIVE"),
                product(SOCK_ID, "SOCK-001", "Sock", "50.00", status));

        create(item(SHOE_ID, 1), item(SOCK_ID, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.detail", containsString(SOCK_ID.toString())));

        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        assertThat(countOrders()).isZero();
    }

    @Test
    void createOrder_productServiceError_returns503() throws Exception {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).willReturn(aResponse().withStatus(500)));

        create(item(SHOE_ID, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));

        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void createOrder_productServiceConnectionFailure_returns503() throws Exception {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        create(item(SHOE_ID, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_SERVICE_UNAVAILABLE"));
    }

    // ---------- 庫存 ----------

    @Test
    void createOrder_insufficientStock_returns409WithoutSavingOrReleasing() throws Exception {
        stubReservation(409, "INSUFFICIENT_STOCK", "insufficient stock: SHOE-001, SOCK-001.");

        create(item(SHOE_ID, 1), item(SOCK_ID, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.detail").value("out of stock: SHOE-001, SOCK-001."));

        // 確定沒有扣，不需要釋放
        INVENTORY_SERVICE.verify(0, postRequestedFor(urlPathEqualTo(RESERVATIONS_PATH + "/release")));
        INVENTORY_SERVICE.verify(1, anyRequestedFor(anyUrl()));
        assertThat(countOrders()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event "
                + "WHERE event_type = 'OrderCreated'", Integer.class)).isZero();
    }

    @Test
    void createOrder_noStockRecord_returns409() throws Exception {
        stubReservation(404, "STOCK_NOT_FOUND", "stock not found: SOCK-001.");

        create(item(SOCK_ID, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.detail", containsString("SOCK-001")));
        assertThat(countOrders()).isZero();
    }

    @Test
    void createOrder_inventoryServerError_releasesAndReturns503() throws Exception {
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse().withStatus(500)));
        stubAnyRelease();

        create(item(SHOE_ID, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_SERVICE_UNAVAILABLE"));

        assertReleasedTheReservedOrder();
        assertThat(countOrders()).isZero();
    }

    @Test
    void createOrder_inventoryTimeout_releasesAndReturns503() throws Exception {
        // 逾時：不知道預扣有沒有成功，一定要釋放
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH))
                .willReturn(aResponse().withStatus(201).withFixedDelay(2000)));
        stubAnyRelease();

        create(item(SHOE_ID, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_SERVICE_UNAVAILABLE"));

        assertReleasedTheReservedOrder();
        assertThat(countOrders()).isZero();
    }

    @Test
    void createOrder_inventoryDownAndReleaseFails_stillReturns503() throws Exception {
        INVENTORY_SERVICE.stubFor(WireMock.post(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        create(item(SHOE_ID, 1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INVENTORY_SERVICE_UNAVAILABLE"));
        assertThat(countOrders()).isZero();
    }

    @Test
    void createOrder_unexpectedInventoryConflict_returns500() throws Exception {
        // 全新的 orderId 不應該出現 RESERVATION_MISMATCH；出現代表程式有問題
        stubReservation(409, "RESERVATION_MISMATCH", "reservation for order x already exists with different items.");

        create(item(SHOE_ID, 1))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(countOrders()).isZero();
    }

    // ---------- 身分與驗證 ----------

    @Test
    void createOrder_missingUserId_returns401WithoutCallingServices() throws Exception {
        mockMvc.perform(post(ORDERS_URL).contentType(MediaType.APPLICATION_JSON)
                .content(body(item(SHOE_ID, 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"items\": []}",
            "{\"items\": [null]}",
            "{\"items\": [{\"qty\": 1}]}",
            "{\"items\": [{\"productId\": \"not-a-uuid\", \"qty\": 1}]}",
            "{\"items\": [{\"productId\": \"%s\"}]}",
            "{\"items\": [{\"productId\": \"%s\", \"qty\": 0}]}",
            "{\"items\": [{\"productId\": \"%s\", \"qty\": 100}]}",
            "{\"items\": [{\"productId\": \"%s\", \"qty\": 1.5}]}" })
    void createOrder_invalidBody_returns400WithoutCallingServices(String template) throws Exception {
        mockMvc.perform(post(ORDERS_URL)
                .header(UserContextHeaders.USER_ID, USER_ID.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template.formatted(SHOE_ID)))
                .andExpect(status().isBadRequest());

        PRODUCT_SERVICE.verify(0, anyRequestedFor(anyUrl()));
        INVENTORY_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void createOrder_moreThan50Items_returns400() throws Exception {
        String[] items = new String[51];
        for (int i = 0; i < items.length; i++) {
            items[i] = item(UUID.randomUUID(), 1);
        }

        create(items)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- helpers ----------

    // 預扣失敗時呼叫的釋放：路徑帶 orderId，事先不知道 id，比對前綴
    private static void stubAnyRelease() {
        INVENTORY_SERVICE.stubFor(WireMock.post(WireMock
                .urlPathMatching(RESERVATIONS_PATH + "/[0-9a-f-]+/release"))
                .willReturn(aResponse().withStatus(200)));
    }

    // 釋放的 orderId 必須和送去預扣的是同一個：從釋放請求的路徑取出 id，再確認預扣請求帶的是同一個
    private static void assertReleasedTheReservedOrder() {
        var releases = INVENTORY_SERVICE.findAll(postRequestedFor(
                WireMock.urlPathMatching(RESERVATIONS_PATH + "/[0-9a-f-]+/release")));
        assertThat(releases).hasSize(1);
        String orderId = releases.getFirst().getUrl().split("/")[5];
        INVENTORY_SERVICE.verify(1, postRequestedFor(urlEqualTo(RESERVATIONS_PATH))
                .withRequestBody(WireMock.matchingJsonPath("$.orderId", equalTo(orderId))));
    }

    private static void stubReservation(int status, String code, String detail) {
        INVENTORY_SERVICE.stubFor(WireMock.post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withBody("""
                        {"status": %d, "code": "%s", "detail": "%s"}
                        """.formatted(status, code, detail))));
    }

    private static void stubProducts(String... products) {
        PRODUCT_SERVICE.stubFor(get(urlPathEqualTo(BATCH_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"products\": [" + String.join(", ", products) + "]}")));
    }

    private static String product(UUID id, String sku, String name, String price, String status) {
        return """
                {"id": "%s", "sku": "%s", "name": "%s", "price": %s, "status": "%s"}
                """.formatted(id, sku, name, price, status);
    }

    private ResultActions create(String... items) throws Exception {
        return mockMvc.perform(post(ORDERS_URL)
                .header(UserContextHeaders.USER_ID, USER_ID.toString())
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(items)));
    }

    private static String body(String... items) {
        return "{\"items\": [" + String.join(", ", items) + "]}";
    }

    private static String item(UUID productId, int qty) {
        return "{\"productId\": \"%s\", \"qty\": %d}".formatted(productId, qty);
    }

    private int countOrders() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.orders", Integer.class);
    }

    private int countItems(UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.order_item WHERE order_id = ?",
                Integer.class, orderId);
    }

    private int countEvent(String type, UUID orderId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM order_service.outbox_event "
                + "WHERE event_type = ? AND aggregate_id = ?", Integer.class, type, orderId.toString());
    }
}

package com.mymicroservice.payment.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
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

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.payment.TestcontainersConfiguration;

// POST /api/v1/payments：order-service 用 WireMock 模擬
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentControllerCreateIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID SYSTEM = new UUID(0L, 0L);

    @RegisterExtension
    static final WireMockExtension ORDER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        registry.add("payment.services.order-uri", ORDER_SERVICE::baseUrl);
        registry.add("payment.services.read-timeout", () -> "1s");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE payment_service.payment");
        orderId = UUID.randomUUID();
        stubOrder(orderId, ALICE, "PENDING_PAYMENT", "399.98");
    }

    @Test
    void createPayment_pendingOrder_createsPendingPaymentWithOrderAmount() throws Exception {
        var response = createAs(ALICE, orderId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.amount").value(399.98))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.completedAt").doesNotExist())
                .andExpect(jsonPath("$.orderNotification").doesNotExist())
                .andReturn().getResponse();
        UUID paymentId = UUID.fromString(JsonPath.read(response.getContentAsString(), "$.id"));

        assertThat(paymentId.version()).isEqualTo(7);
        assertThat(response.getHeader("Location")).isEqualTo("/api/v1/payments/" + paymentId);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM payment_service.payment WHERE id = ?",
                paymentId);
        assertThat(row.get("user_id")).isEqualTo(ALICE);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("399.98");
        assertThat(row.get("status")).isEqualTo("PENDING");

        assertThat(row.get("created_by")).isEqualTo(ALICE);
    }

    @Test
    void createPayment_amountInBody_isIgnored() throws Exception {
        // 金額一律取自 order-service
        createAs(ALICE, "{\"orderId\": \"%s\", \"amount\": 0.01}".formatted(orderId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(399.98));
    }

    @Test
    void createPayment_again_returnsExistingPendingPayment() throws Exception {
        String first = createAs(ALICE, orderId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        createAs(ALICE, orderId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));

        assertThat(countPayments()).isEqualTo(1);
    }

    @Test
    void createPayment_afterFailedPayment_createsNewOne() throws Exception {
        insertPayment(orderId, "FAILED");

        createAs(ALICE, orderId).andExpect(status().isCreated());

        assertThat(countPayments()).isEqualTo(2);
    }

    @Test
    void createPayment_alreadySucceeded_returns409() throws Exception {
        insertPayment(orderId, "SUCCEEDED");
        stubOrder(orderId, ALICE, "PAID", "399.98");

        createAs(ALICE, orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PAID"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "CANCELLED", "PAID", "SHIPPED" })
    void createPayment_orderNotPendingPayment_returns409(String status) throws Exception {
        stubOrder(orderId, ALICE, status, "399.98");

        createAs(ALICE, orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"))
                .andExpect(jsonPath("$.detail").value(
                        "order " + orderId + " cannot be paid in status " + status + "."));
        assertThat(countPayments()).isZero();
    }

    @Test
    void createPayment_otherUsersOrder_returns404() throws Exception {
        createAs(BOB, orderId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        assertThat(countPayments()).isZero();
    }

    @Test
    void createPayment_unknownOrder_returns404() throws Exception {
        UUID unknown = UUID.randomUUID();
        ORDER_SERVICE.stubFor(get(urlEqualTo("/internal/v1/orders/" + unknown)).willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
                .withBody("{\"status\": 404, \"code\": \"ORDER_NOT_FOUND\"}")));

        createAs(ALICE, unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void createPayment_orderServiceDown_returns503() throws Exception {
        ORDER_SERVICE.stubFor(get(urlEqualTo("/internal/v1/orders/" + orderId))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        createAs(ALICE, orderId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_SERVICE_UNAVAILABLE"));
    }

    @Test
    void createPayment_orderServiceTimeout_returns503() throws Exception {
        ORDER_SERVICE.stubFor(get(urlEqualTo("/internal/v1/orders/" + orderId))
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        createAs(ALICE, orderId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_SERVICE_UNAVAILABLE"));
    }

    @Test
    void createPayment_missingUserId_returns401WithoutCallingOrderService() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": \"%s\"}".formatted(orderId)))
                .andExpect(status().isUnauthorized());

        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"orderId\": null}", "{\"orderId\": \"not-a-uuid\"}" })
    void createPayment_invalidBody_returns400(String body) throws Exception {
        createAs(ALICE, body).andExpect(status().isBadRequest());

        ORDER_SERVICE.verify(0, anyRequestedFor(anyUrl()));
    }

    // ---------- helpers ----------

    private static void stubOrder(UUID orderId, UUID userId, String status, String amount) {
        ORDER_SERVICE.stubFor(get(urlEqualTo("/internal/v1/orders/" + orderId)).willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("""
                        {"id": "%s", "userId": "%s", "status": "%s", "totalAmount": %s}
                        """.formatted(orderId, userId, status, amount))));
    }

    private void insertPayment(UUID orderId, String status) {
        jdbcTemplate.update("""
                INSERT INTO payment_service.payment
                    (id, order_id, user_id, amount, status, created_by, updated_by)
                VALUES (?, ?, ?, 399.98, ?, ?, ?)
                """, UUID.randomUUID(), orderId, ALICE, status, SYSTEM, SYSTEM);
    }

    private ResultActions createAs(UUID userId, UUID orderId) throws Exception {
        return createAs(userId, "{\"orderId\": \"%s\"}".formatted(orderId));
    }

    private ResultActions createAs(UUID userId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/payments")
                .header(UserContextHeaders.USER_ID, userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int countPayments() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM payment_service.payment", Integer.class);
    }
}

package com.mymicroservice.payment.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
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

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mymicroservice.common.resilience.DownstreamCallers;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.payment.TestcontainersConfiguration;
import com.mymicroservice.payment.clients.OrderClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

// payment → order-service 的重試與熔斷（階段 B2）：4 次記錄到的失敗就打開熔斷器，每次呼叫最多嘗試 3 次
@SpringBootTest(properties = "payment.mock-callback.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentOrderClientResilienceIntegrationTests {
    private static final UUID ALICE = UUID.randomUUID();

    @RegisterExtension
    static final WireMockExtension ORDER_SERVICE = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("payment.services.order-uri", ORDER_SERVICE::baseUrl);
        registry.add("payment.services.resilience.sliding-window-size", () -> "4");
        registry.add("payment.services.resilience.minimum-number-of-calls", () -> "4");
        registry.add("payment.services.resilience.failure-rate-threshold", () -> "50");
        registry.add("payment.services.resilience.wait-duration-in-open-state", () -> "1m");
        registry.add("payment.services.resilience.retry-max-attempts", () -> "3");
        registry.add("payment.services.resilience.retry-initial-backoff", () -> "1ms");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DownstreamCallers callers;
    @Autowired
    private OrderClient orderClient;

    private UUID orderId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE payment_service.payment");
        ORDER_SERVICE.resetAll();
        callers.circuitBreakers().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        orderId = UUID.randomUUID();
    }

    @Test
    void findOrder_failsOnceThenRecovers_isRetried() throws Exception {
        ORDER_SERVICE.stubFor(get(urlPathMatching("/internal/v1/orders/.+")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("up"));
        ORDER_SERVICE.stubFor(get(urlPathMatching("/internal/v1/orders/.+")).inScenario("flaky")
                .whenScenarioStateIs("up").willReturn(orderJson()));

        createPayment().andExpect(status().isCreated());

        ORDER_SERVICE.verify(2, getRequestedFor(urlPathMatching("/internal/v1/orders/.+")));
    }

    @Test
    void findOrder_orderServiceDown_opensTheBreaker_andLaterPaymentsFailFast() throws Exception {
        ORDER_SERVICE.stubFor(get(urlPathMatching("/internal/v1/orders/.+")).willReturn(aResponse().withStatus(503)));

        createPayment().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_SERVICE_UNAVAILABLE"));
        createPayment().andExpect(status().isServiceUnavailable());
        assertThat(callers.circuitBreakers().circuitBreaker("order-service").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);
        int callsWhenOpened = ORDER_SERVICE
                .countRequestsMatching(getRequestedFor(urlPathMatching("/internal/v1/orders/.+")).build()).getCount();

        createPayment().andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ORDER_SERVICE_UNAVAILABLE"));

        ORDER_SERVICE.verify(callsWhenOpened, getRequestedFor(urlPathMatching("/internal/v1/orders/.+")));
    }

    @Test
    void findOrder_unknownOrder_404IsNotAFailure() {
        ORDER_SERVICE.stubFor(get(urlPathMatching("/internal/v1/orders/.+")).willReturn(aResponse().withStatus(404)));

        for (int i = 0; i < 6; i++) {
            assertThat(orderClient.findOrder(orderId)).isEmpty();
        }

        assertThat(callers.circuitBreakers().circuitBreaker("order-service").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void notifyPaid_failsOnceThenRecovers_isRetriedAndNotified() {
        ORDER_SERVICE.stubFor(post(urlPathMatching("/internal/v1/orders/.+/paid")).inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("up"));
        ORDER_SERVICE.stubFor(post(urlPathMatching("/internal/v1/orders/.+/paid")).inScenario("flaky")
                .whenScenarioStateIs("up").willReturn(aResponse().withStatus(200)));

        assertThat(orderClient.notifyPaid(orderId)).isEqualTo(OrderClient.NotifyResult.NOTIFIED);

        ORDER_SERVICE.verify(2, postRequestedFor(urlPathMatching("/internal/v1/orders/.+/paid")));
    }

    @Test
    void notifyPaid_breakerOpen_returnsFailedWithoutCalling() {
        callers.circuitBreakers().circuitBreaker("order-service").transitionToOpenState();

        assertThat(orderClient.notifyPaid(orderId)).isEqualTo(OrderClient.NotifyResult.FAILED);

        ORDER_SERVICE.verify(0, postRequestedFor(urlPathMatching("/internal/v1/orders/.+/paid")));
    }

    @Test
    void notifyPaid_orderCancelled_409IsTheBusinessResultNotAFailure() {
        ORDER_SERVICE.stubFor(post(urlPathMatching("/internal/v1/orders/.+/paid")).willReturn(aResponse()
                .withStatus(409).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"status\":409,\"code\":\"ORDER_ALREADY_CANCELLED\"}")));

        for (int i = 0; i < 6; i++) {
            assertThat(orderClient.notifyPaid(orderId)).isEqualTo(OrderClient.NotifyResult.REJECTED);
        }

        ORDER_SERVICE.verify(6, postRequestedFor(urlPathMatching("/internal/v1/orders/.+/paid")));
        assertThat(callers.circuitBreakers().circuitBreaker("order-service").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ---------- helpers ----------

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder orderJson() {
        return aResponse().withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).withBody(
                "{\"id\": \"%s\", \"userId\": \"%s\", \"status\": \"PENDING_PAYMENT\", \"totalAmount\": 100.00}"
                        .formatted(orderId, ALICE));
    }

    private ResultActions createPayment() throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/payments")
                .header(UserContextHeaders.USER_ID, ALICE.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": \"%s\"}".formatted(orderId)));
    }
}

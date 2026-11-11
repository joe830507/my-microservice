package com.mymicroservice.payment.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.payment.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

// SEC-01：模擬金流回呼讓顧客自己把付款標成成功，所以預設不存在；沒有設定 payment.mock-callback.enabled=true 時，
// 這支 API 根本沒有註冊（回 404），不是「註冊了但拒絕」
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MockCallbackDisabledByDefaultIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApplicationContext context;

    @Test
    void mockCallbackController_isNotRegistered() {
        assertThat(context.getBeanNamesForType(MockPaymentController.class)).isEmpty();
        // 一般的付款 API 照常存在
        assertThat(context.getBeanNamesForType(PaymentController.class)).hasSize(1);
    }

    @Test
    void mockCallback_returns404_forCustomerAndAdmin() throws Exception {
        String body = "{\"result\":\"SUCCESS\"}";
        String paymentId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/mock-callback")
                .header(UserContextHeaders.USER_ID, UUID.randomUUID().toString())
                .header(UserContextHeaders.USER_ROLES, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/mock-callback")
                .header(UserContextHeaders.USER_ID, UUID.randomUUID().toString())
                .header(UserContextHeaders.USER_ROLES, "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }
}

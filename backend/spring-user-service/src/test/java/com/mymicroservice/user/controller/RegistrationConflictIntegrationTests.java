package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.service.AuthService;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "app.events.outbox-poll-interval=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RegistrationConflictIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired AuthService auth;
    @MockitoSpyBean PasswordEncoder encoder;

    @RepeatedTest(5)
    void concurrentRegistrationReturnsOneCreatedAndOneEmailConflict() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        // encode 在 existsByEmail 之後：確保兩個請求都查不到帳號，再一起寫入。
        CountDownLatch encoded = new CountDownLatch(2);
        doAnswer(invocation -> {
            Object hash = invocation.callRealMethod();
            encoded.countDown();
            if (!encoded.await(10, TimeUnit.SECONDS)) throw new AssertionError("Registration barrier timed out");
            return hash;
        }).when(encoder).encode(any());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var request = (java.util.concurrent.Callable<org.springframework.mock.web.MockHttpServletResponse>) () -> {
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RegisterReqDto(email, "password123", "Concurrent"))))
                        .andReturn().getResponse();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            start.countDown();
            var a = first.get(30, TimeUnit.SECONDS);
            var b = second.get(30, TimeUnit.SECONDS);
            assertThat(java.util.List.of(a.getStatus(), b.getStatus())).containsExactlyInAnyOrder(201, 409);
            var conflict = a.getStatus() == 409 ? a : b;
            assertThat(mapper.readTree(conflict.getContentAsString()).get("code").asString()).isEqualTo("EMAIL_ALREADY_EXISTS");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_service.users WHERE email=?", Long.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_service.outbox_event WHERE event_type='UserRegistered' AND payload->'payload'->>'email'=?", Long.class, email)).isEqualTo(1);
    }

    @Test
    void otherIntegrityViolationIsNotConvertedAndRollsBackOutbox() {
        String email = UUID.randomUUID() + "@example.com";
        // 直接呼叫 Service 繞過 MVC validation，觸發 display_name VARCHAR(50) 限制。
        assertThatThrownBy(() -> auth.register(new RegisterReqDto(email, "password123", "x".repeat(51))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_service.users WHERE email=?", Long.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_service.outbox_event WHERE payload->'payload'->>'email'=?", Long.class, email)).isZero();
    }
}

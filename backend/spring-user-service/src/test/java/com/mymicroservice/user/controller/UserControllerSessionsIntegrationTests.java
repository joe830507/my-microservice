package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.user.TestcontainersConfiguration;

// DELETE /api/v1/users/me/sessions：登出所有裝置
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserControllerSessionsIntegrationTests {
    private static final String SESSIONS_URL = "/api/v1/users/me/sessions";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
    }

    @Test
    void revokeAllSessions_revokesEveryRefreshTokenOfCaller() throws Exception {
        UUID userId = register("alice@example.com");
        String phone = login("alice@example.com");
        String laptop = login("alice@example.com");

        revokeAllSessions(userId).andExpect(status().isNoContent());

        assertThat(activeTokenCount(userId)).isZero();
        refresh(phone).andExpect(status().isUnauthorized());
        refresh(laptop).andExpect(status().isUnauthorized());
    }

    @Test
    void revokeAllSessions_doesNotAffectOtherUsers() throws Exception {
        UUID aliceId = register("alice@example.com");
        UUID bobId = register("bob@example.com");
        login("alice@example.com");
        String bobToken = login("bob@example.com");

        revokeAllSessions(aliceId).andExpect(status().isNoContent());

        assertThat(activeTokenCount(bobId)).isEqualTo(1);
        refresh(bobToken).andExpect(status().isOk());
    }

    @Test
    void revokeAllSessions_recentlyRotatedTokenLosesGracePeriod() throws Exception {
        // 剛輪替掉的舊 token 還在寬限時間內，但新 token 已被撤銷，不能再換發
        UUID userId = register("alice@example.com");
        String original = login("alice@example.com");
        refresh(original).andExpect(status().isOk());

        revokeAllSessions(userId).andExpect(status().isNoContent());

        refresh(original).andExpect(status().isUnauthorized());
        assertThat(activeTokenCount(userId)).isZero();
    }

    @Test
    void revokeAllSessions_canLoginAgainAfterwards() throws Exception {
        UUID userId = register("alice@example.com");
        login("alice@example.com");
        revokeAllSessions(userId).andExpect(status().isNoContent());

        String newToken = login("alice@example.com");

        refresh(newToken).andExpect(status().isOk());
    }

    @Test
    void revokeAllSessions_noTokens_returns204() throws Exception {
        UUID userId = register("alice@example.com");

        revokeAllSessions(userId).andExpect(status().isNoContent());
    }

    @Test
    void revokeAllSessions_missingUserId_returns401() throws Exception {
        mockMvc.perform(delete(SESSIONS_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = { " ", "not-a-uuid" })
    void revokeAllSessions_malformedUserId_returns401(String header) throws Exception {
        mockMvc.perform(delete(SESSIONS_URL).header(UserContextHeaders.USER_ID, header))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- helpers ----------

    private ResultActions revokeAllSessions(UUID userId) throws Exception {
        return mockMvc.perform(delete(SESSIONS_URL).header(UserContextHeaders.USER_ID, userId.toString()));
    }

    private UUID register(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "displayName": "User"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.refreshToken");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)));
    }

    private int activeTokenCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_service.refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }
}

package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.service.impl.RefreshTokenGenerator;

// POST /api/v1/auth/logout：撤銷 body 帶來的 refresh token；DB 為 Testcontainers 啟動的真實 PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerLogoutIntegrationTests {
    private static final String LOGOUT_URL = "/api/v1/auth/logout";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RefreshTokenGenerator refreshTokenGenerator;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
    }

    // ---------- 成功 ----------

    @Test
    void logout_validToken_returns204AndRevokesIt() throws Exception {
        register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");

        logout(refreshToken)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(revokedAtOf(refreshToken)).isNotNull();
        // 不是輪替，不會有寬限期
        assertThat(replacedByOf(refreshToken)).isNull();
    }

    @Test
    void logout_tokenCannotBeUsedToRefreshAfterwards() throws Exception {
        register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");
        logout(refreshToken).andExpect(status().isNoContent());

        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void logout_onlyRevokesThatToken_otherDevicesStayLoggedIn() throws Exception {
        UUID userId = register("alice@example.com");
        String thisDevice = loginForRefreshToken("alice@example.com");
        String otherDevice = loginForRefreshToken("alice@example.com");

        logout(thisDevice).andExpect(status().isNoContent());

        assertThat(activeTokenCount(userId)).isEqualTo(1);
        refresh(otherDevice).andExpect(status().isOk());
    }

    @Test
    void logout_doesNotAffectOtherUsers() throws Exception {
        register("alice@example.com");
        UUID bobId = register("bob@example.com");
        String aliceToken = loginForRefreshToken("alice@example.com");
        loginForRefreshToken("bob@example.com");

        logout(aliceToken).andExpect(status().isNoContent());

        assertThat(activeTokenCount(bobId)).isEqualTo(1);
    }

    @Test
    void logout_afterRotation_previousTokenLosesGracePeriod() throws Exception {
        // 輪替後登出新的 token；舊 token 雖然還在寬限時間內，但換成的新 token 已失效，不能再換發
        UUID userId = register("alice@example.com");
        String original = loginForRefreshToken("alice@example.com");
        String rotated = JsonPath.read(refresh(original).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");

        logout(rotated).andExpect(status().isNoContent());

        refresh(original).andExpect(status().isUnauthorized());
        assertThat(activeTokenCount(userId)).isZero();
    }

    // ---------- 不回錯誤的情況 ----------

    @Test
    void logout_unknownToken_returns204() throws Exception {
        logout(refreshTokenGenerator.generate()).andExpect(status().isNoContent());
    }

    @Test
    void logout_twice_returns204AndKeepsFirstRevocationTime() throws Exception {
        register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");
        logout(refreshToken).andExpect(status().isNoContent());
        Instant firstRevokedAt = revokedAtOf(refreshToken);

        logout(refreshToken).andExpect(status().isNoContent());

        assertThat(revokedAtOf(refreshToken)).isEqualTo(firstRevokedAt);
    }

    @Test
    void logout_expiredToken_returns204() throws Exception {
        register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");
        jdbcTemplate.update("UPDATE user_service.refresh_token SET expires_at = now() - interval '1 second' "
                + "WHERE token_hash = ?", refreshTokenGenerator.hash(refreshToken));

        logout(refreshToken).andExpect(status().isNoContent());
    }

    @Test
    void logout_doesNotRequireIdentityHeaders() throws Exception {
        // access token 可能已過期；只靠 refresh token 證明身分，不需要 X-User-Id
        register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");

        mockMvc.perform(post(LOGOUT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(refreshToken)))
                .andExpect(status().isNoContent());
    }

    // ---------- 欄位驗證 ----------

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("缺少 refreshToken", "{}"),
                Arguments.of("refreshToken 空白", "{\"refreshToken\": \" \"}"),
                Arguments.of("refreshToken 過長", "{\"refreshToken\": \"" + "a".repeat(101) + "\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void logout_invalidBody_returns400(String caseName, String body) throws Exception {
        mockMvc.perform(post(LOGOUT_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- helpers ----------

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

    private String loginForRefreshToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.refreshToken");
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(post(LOGOUT_URL).contentType(MediaType.APPLICATION_JSON).content(body(refreshToken)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(refreshToken)));
    }

    private static String body(String refreshToken) {
        return """
                {"refreshToken": "%s"}
                """.formatted(refreshToken);
    }

    private Instant revokedAtOf(String refreshToken) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM user_service.refresh_token WHERE token_hash = ?", Instant.class,
                refreshTokenGenerator.hash(refreshToken));
    }

    private UUID replacedByOf(String refreshToken) {
        return jdbcTemplate.queryForObject(
                "SELECT replaced_by FROM user_service.refresh_token WHERE token_hash = ?", UUID.class,
                refreshTokenGenerator.hash(refreshToken));
    }

    private int activeTokenCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_service.refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }
}

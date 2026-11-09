package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.properties.JwtProperties;
import com.mymicroservice.user.repositories.RefreshTokenRepository;
import com.mymicroservice.user.service.impl.RefreshTokenGenerator;
import com.nimbusds.jose.jwk.RSAKey;

// POST /api/v1/auth/refresh 與登入時簽發的 refresh token；DB 為 Testcontainers 啟動的真實 PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerRefreshIntegrationTests {
    private static final String REFRESH_URL = "/api/v1/auth/refresh";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private RefreshTokenGenerator refreshTokenGenerator;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RSAKey rsaKey;

    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        // refresh_token 透過 FK 一併清掉
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
        jwtDecoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    }

    // ---------- 登入時簽發 ----------

    @Test
    void login_returnsRefreshTokenAndItsTtl() throws Exception {
        register("alice@example.com");

        login("alice@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshExpiresIn").value(jwtProperties.refreshTokenTtl().toSeconds()));
    }

    @Test
    void login_storesOnlyHashOfRefreshToken() throws Exception {
        UUID userId = register("alice@example.com");

        String refreshToken = loginForRefreshToken("alice@example.com");

        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT token_hash FROM user_service.refresh_token WHERE user_id = ?", String.class, userId);
        assertThat(hashes).containsExactly(refreshTokenGenerator.hash(refreshToken));
        assertThat(hashes.getFirst()).isNotEqualTo(refreshToken).hasSize(64);
        // 32 bytes 的 base64url，不補 =
        assertThat(refreshToken).hasSize(43).doesNotContain("=", "+", "/");
    }

    @Test
    void login_eachLoginIssuesSeparateRefreshToken() throws Exception {
        UUID userId = register("alice@example.com");

        String first = loginForRefreshToken("alice@example.com");
        String second = loginForRefreshToken("alice@example.com");

        // 多個裝置各自登入，各有一組，互不影響
        assertThat(first).isNotEqualTo(second);
        assertThat(activeTokenCount(userId)).isEqualTo(2);
    }

    // ---------- 成功換發 ----------

    @Test
    void refresh_validToken_returnsNewAccessTokenAndRotatedRefreshToken() throws Exception {
        UUID userId = register("alice@example.com");
        String oldRefreshToken = loginForRefreshToken("alice@example.com");

        String response = refresh(oldRefreshToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(jwtProperties.accessTokenTtl().toSeconds()))
                .andExpect(jsonPath("$.refreshExpiresIn").value(jwtProperties.refreshTokenTtl().toSeconds()))
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = jwtDecoder.decode(JsonPath.read(response, "$.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");

        String newRefreshToken = JsonPath.read(response, "$.refreshToken");
        assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken);

        // 舊的已撤銷，只剩新的有效
        assertThat(revokedAtOf(oldRefreshToken)).isNotNull();
        assertThat(revokedAtOf(newRefreshToken)).isNull();
        assertThat(activeTokenCount(userId)).isEqualTo(1);
    }

    @Test
    void refresh_rotatedTokenCanBeUsedForNextRefresh() throws Exception {
        register("alice@example.com");
        String token = loginForRefreshToken("alice@example.com");

        for (int i = 0; i < 3; i++) {
            token = JsonPath.read(refresh(token).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(), "$.refreshToken");
        }
    }

    @Test
    void refresh_reflectsRoleChangesSinceLogin() throws Exception {
        UUID userId = register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");

        jdbcTemplate.update("INSERT INTO user_service.user_role (user_id, role) VALUES (?, 'ADMIN')", userId);

        String response = refresh(refreshToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Jwt jwt = jwtDecoder.decode(JsonPath.read(response, "$.accessToken"));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN", "CUSTOMER");
    }

    // ---------- 重複使用偵測 ----------

    @Test
    void refresh_reusedToken_returns401AndRevokesAllTokensOfUser() throws Exception {
        UUID userId = register("alice@example.com");
        String stolen = loginForRefreshToken("alice@example.com");
        String otherDevice = loginForRefreshToken("alice@example.com");

        // 合法使用者先換發一次，拿到新的
        String rotated = JsonPath.read(refresh(stolen).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");

        // 攻擊者拿舊的再用一次
        refresh(stolen)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        // 撤銷要被保留（不能隨例外一起 rollback）：該使用者所有 token 都失效
        assertThat(activeTokenCount(userId)).isZero();
        refresh(rotated).andExpect(status().isUnauthorized());
        refresh(otherDevice).andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_reuseDetection_doesNotAffectOtherUsers() throws Exception {
        register("alice@example.com");
        UUID bobId = register("bob@example.com");
        String aliceToken = loginForRefreshToken("alice@example.com");
        String bobToken = loginForRefreshToken("bob@example.com");

        refresh(aliceToken).andExpect(status().isOk());
        refresh(aliceToken).andExpect(status().isUnauthorized());

        assertThat(activeTokenCount(bobId)).isEqualTo(1);
        refresh(bobToken).andExpect(status().isOk());
    }

    @Test
    void revokeIfActive_secondCallReturnsZero() throws Exception {
        // 同一個 token 併發 refresh 時，靠條件式 UPDATE 保證只有一個請求能撤銷成功
        register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");
        UUID tokenId = refreshTokenRepository.findByTokenHash(refreshTokenGenerator.hash(refreshToken))
                .orElseThrow().getId();

        Integer first = transactionTemplate.execute(status -> refreshTokenRepository.revokeIfActive(tokenId,
                Instant.now()));
        Integer second = transactionTemplate.execute(status -> refreshTokenRepository.revokeIfActive(tokenId,
                Instant.now()));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    // ---------- 其他失效情況 ----------

    @Test
    void refresh_unknownToken_returns401() throws Exception {
        refresh(refreshTokenGenerator.generate())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void refresh_expiredToken_returns401WithoutRevokingOtherTokens() throws Exception {
        UUID userId = register("alice@example.com");
        String expired = loginForRefreshToken("alice@example.com");
        loginForRefreshToken("alice@example.com");
        jdbcTemplate.update("UPDATE user_service.refresh_token SET expires_at = now() - interval '1 second' "
                + "WHERE token_hash = ?", refreshTokenGenerator.hash(expired));

        refresh(expired)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        // 過期是自然失效，不是竊取，另一個裝置的 token 不受影響
        assertThat(activeTokenCount(userId)).isEqualTo(2);
    }

    @Test
    void refresh_disabledUser_returns403AndRevokesAllTokens() throws Exception {
        UUID userId = register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");
        loginForRefreshToken("alice@example.com");
        jdbcTemplate.update("UPDATE user_service.users SET status = 'DISABLED' WHERE id = ?", userId);

        refresh(refreshToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DISABLED"));

        assertThat(activeTokenCount(userId)).isZero();
    }

    @Test
    void refresh_deletedUser_tokensAreRemovedByCascade() throws Exception {
        UUID userId = register("alice@example.com");
        String refreshToken = loginForRefreshToken("alice@example.com");
        jdbcTemplate.update("DELETE FROM user_service.users WHERE id = ?", userId);

        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
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
    void refresh_invalidBody_returns400(String caseName, String body) throws Exception {
        mockMvc.perform(post(REFRESH_URL).contentType(MediaType.APPLICATION_JSON).content(body))
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

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, PASSWORD)));
    }

    private String loginForRefreshToken(String email) throws Exception {
        String response = login(email).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.refreshToken");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post(REFRESH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)));
    }

    private Instant revokedAtOf(String refreshToken) {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM user_service.refresh_token WHERE token_hash = ?", Instant.class,
                refreshTokenGenerator.hash(refreshToken));
    }

    private int activeTokenCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_service.refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
    }
}

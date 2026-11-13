package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
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

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.properties.JwtProperties;
import com.nimbusds.jose.jwk.RSAKey;

// POST /api/v1/auth/login 的整合測試：使用者一律經由註冊 API 建立，確保密碼雜湊與實際流程一致
// 簽出的 JWT 用公鑰驗簽後再檢查 claims，等同 Gateway 驗 token 的方式
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerLoginIntegrationTests {
    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String LOGIN_URL = "/api/v1/auth/login";

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RSAKey rsaKey;
    @Autowired
    private JwtProperties jwtProperties;

    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        // 每個測試都從空的 DB 開始；user_role 透過 FK 一併清掉
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
        // 只拿公鑰驗簽，確認 Gateway 用 JWKS 的公鑰就能驗過
        jwtDecoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
    }

    // ---------- 成功 ----------

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Test
    void bcryptDoesNotHoldAnActiveDatabaseTransaction() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(passwordEncoder).matches(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        registerUser(EMAIL, PASSWORD);
        login(EMAIL, PASSWORD).andExpect(status().isOk());
        login(EMAIL, "wrong-password").andExpect(status().isUnauthorized());
    }

    @Test
    void failedLoginWritesNoRefreshTokenAndSuccessMatchesStoredHash() throws Exception {
        registerUser(EMAIL, PASSWORD);
        login(EMAIL, "incorrect-password").andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_service.refresh_token", Long.class)).isZero();
        String token = com.mymicroservice.user.RefreshCookies.tokenOf(
                login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn());
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM user_service.refresh_token", String.class)).isEqualTo(hash);
    }

    @Test
    void login_validCredentials_returns200WithBearerToken() throws Exception {
        registerUser(EMAIL, PASSWORD);

        login(EMAIL, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(jwtProperties.accessTokenTtl().toSeconds()))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                // 不應回傳任何使用者的敏感資料
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void login_validCredentials_issuesTokenWithExpectedClaims() throws Exception {
        UUID userId = registerUser(EMAIL, PASSWORD);

        Jwt jwt = loginAndDecode(EMAIL, PASSWORD);

        // header：RS256，kid 對應設定，Gateway 靠它從 JWKS 找公鑰
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.getHeaders()).containsEntry("kid", jwtProperties.keyId());

        // sub 是 userId 而非 email
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getSubject()).isNotEqualTo(EMAIL);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(jwtProperties.issuer());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");

        // 有效時間等於設定的 TTL
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .isEqualTo(jwtProperties.accessTokenTtl());

        // payload 不應帶 email 等個資
        assertThat(jwt.getClaims()).doesNotContainKeys("email", "password", "passwordHash");
    }

    @Test
    void login_userWithMultipleRoles_tokenContainsAllRolesSorted() throws Exception {
        UUID userId = registerUser(EMAIL, PASSWORD);
        jdbcTemplate.update("INSERT INTO user_service.user_role (user_id, role) VALUES (?, 'ADMIN')", userId);

        Jwt jwt = loginAndDecode(EMAIL, PASSWORD);

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN", "CUSTOMER");
    }

    @Test
    void login_doesNotModifyUser() throws Exception {
        UUID userId = registerUser(EMAIL, PASSWORD);
        Map<String, Object> before = userRow(userId);

        login(EMAIL, PASSWORD).andExpect(status().isOk());

        // 登入是唯讀交易，不應更動版本或稽核欄位
        Map<String, Object> after = userRow(userId);
        assertThat(after.get("version")).isEqualTo(before.get("version"));
        assertThat(after.get("updated_at")).isEqualTo(before.get("updated_at"));
    }

    // ---------- 帳密錯誤 ----------

    @Test
    void login_wrongPassword_returns401() throws Exception {
        registerUser(EMAIL, PASSWORD);

        login(EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void login_unknownEmail_returns401() throws Exception {
        login("nobody@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_unknownEmailAndWrongPassword_returnIdenticalResponses() throws Exception {
        // 兩種情況的回應必須完全相同，否則可以拿來探測 email 是否已註冊
        registerUser(EMAIL, PASSWORD);

        String wrongPasswordBody = login(EMAIL, "wrong-password")
                .andReturn().getResponse().getContentAsString();
        String unknownEmailBody = login("nobody@example.com", "wrong-password")
                .andReturn().getResponse().getContentAsString();

        // instance（請求路徑）兩者相同，可以直接比較整個 body
        assertThat(unknownEmailBody).isEqualTo(wrongPasswordBody);
    }

    @Test
    void login_emailDiffersOnlyInCase_returns401() throws Exception {
        // V1 的設計：email 區分大小寫
        registerUser(EMAIL, PASSWORD);

        login("Alice@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ---------- 帳號停用 ----------

    @Test
    void login_disabledUserWithCorrectPassword_returns403() throws Exception {
        UUID userId = registerUser(EMAIL, PASSWORD);
        disable(userId);

        login(EMAIL, PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DISABLED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void login_disabledUserWithWrongPassword_returns401NotRevealingStatus() throws Exception {
        // 密碼錯誤時不應透露帳號已停用（也就等於透露帳號存在）
        UUID userId = registerUser(EMAIL, PASSWORD);
        disable(userId);

        login(EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ---------- 欄位驗證 ----------

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("email 空白", "", PASSWORD, "email"),
                Arguments.of("email 超過 254 字", "a".repeat(250) + "@x.com", PASSWORD, "email"),
                Arguments.of("密碼空白", EMAIL, "", "password"),
                Arguments.of("密碼超過 72 字", EMAIL, "a".repeat(73), "password"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void login_invalidField_returns400(String caseName, String email, String password, String invalidField)
            throws Exception {
        login(email, password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // detail 的格式是「欄位: 訊息」，確認指出的是正確的欄位
                .andExpect(jsonPath("$.detail", containsString(invalidField + ":")));
    }

    @Test
    void login_shortPassword_isNotRejectedByValidation() throws Exception {
        // 長度下限只在註冊時檢查；登入時短密碼應走一般的帳密錯誤，而非 400
        registerUser(EMAIL, PASSWORD);

        login(EMAIL, "short")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", not("VALIDATION_FAILED")));
    }

    @Test
    void login_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not json"))
                .andExpect(status().isBadRequest());
    }

    // ---------- helpers ----------

    // 經由註冊 API 建立使用者，回傳 userId
    private UUID registerUser(String email, String password) throws Exception {
        String body = """
                {"email": "%s", "password": "%s", "displayName": "Alice"}
                """.formatted(email, password);
        String response = mockMvc.perform(post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private ResultActions login(String email, String password) throws Exception {
        // 用字串組 JSON，測試內容就是實際送出的 body
        String body = """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
        return mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // 登入成功並用公鑰驗簽、解出 JWT；簽章或 exp 不合法時 decode 會直接丟例外
    private Jwt loginAndDecode(String email, String password) throws Exception {
        String response = login(email, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jwtDecoder.decode(JsonPath.read(response, "$.accessToken"));
    }

    private void disable(UUID userId) {
        jdbcTemplate.update("UPDATE user_service.users SET status = 'DISABLED' WHERE id = ?", userId);
    }

    private Map<String, Object> userRow(UUID userId) {
        return jdbcTemplate.queryForMap("SELECT * FROM user_service.users WHERE id = ?", userId);
    }
}

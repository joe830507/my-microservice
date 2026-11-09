package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mymicroservice.common.audit.HeaderAuditorAware;
import com.mymicroservice.user.TestcontainersConfiguration;

// POST /api/v1/auth/register 的整合測試：經過完整的 MVC（驗證、例外處理）寫入 Testcontainers 啟動的真實 PostgreSQL
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerRegisterIntegrationTests {
    private static final String REGISTER_URL = "/api/v1/auth/register";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        // 每個測試都從空的 DB 開始；user_role 透過 FK 一併清掉
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
    }

    // ---------- 成功 ----------

    @Test
    void register_validRequest_returns201WithUserWithoutPassword() throws Exception {
        register("alice@example.com", "password123", "Alice")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles", contains("CUSTOMER")))
                .andExpect(jsonPath("$.createdAt", notNullValue()))
                // 雜湊值也不應離開服務
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_validRequest_storesHashedPasswordAndDefaults() throws Exception {
        register("alice@example.com", "password123", "Alice").andExpect(status().isCreated());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM user_service.users WHERE email = ?", "alice@example.com");
        String passwordHash = (String) row.get("password_hash");

        // DelegatingPasswordEncoder 的格式，且能用原密碼比對成功
        assertThat(passwordHash).startsWith("{bcrypt}").doesNotContain("password123");
        assertThat(passwordEncoder.matches("password123", passwordHash)).isTrue();
        assertThat(row.get("status")).isEqualTo("ACTIVE");

        // 註冊沒有 X-User-Id，建立者記為系統
        assertThat(row.get("created_by")).isEqualTo(HeaderAuditorAware.SYSTEM_USER_ID);
        assertThat(row.get("updated_by")).isEqualTo(HeaderAuditorAware.SYSTEM_USER_ID);

        assertThat(rolesOf((UUID) row.get("id"))).containsExactly("CUSTOMER");
    }

    @Test
    void register_requestTriesToSetRolesAndStatus_ignoresThem() throws Exception {
        // RegisterReqDto 沒有 roles、status 欄位，前端不能自己指定成 ADMIN
        mockMvc.perform(post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "email": "mallory@example.com",
                          "password": "password123",
                          "displayName": "Mallory",
                          "roles": ["ADMIN"],
                          "status": "DISABLED"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles", contains("CUSTOMER")))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        UUID id = jdbcTemplate.queryForObject(
                "SELECT id FROM user_service.users WHERE email = ?", UUID.class, "mallory@example.com");
        assertThat(rolesOf(id)).containsExactly("CUSTOMER");
    }

    // ---------- email 重複 ----------

    @Test
    void register_emailAlreadyExists_returns409() throws Exception {
        register("alice@example.com", "password123", "Alice").andExpect(status().isCreated());

        register("alice@example.com", "another-password", "Alice 2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));

        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void register_emailDiffersOnlyInCase_treatedAsDifferentAccounts() throws Exception {
        // V1 的設計：email 區分大小寫
        register("Alice@example.com", "password123", "Alice").andExpect(status().isCreated());
        register("alice@example.com", "password123", "alice").andExpect(status().isCreated());

        assertThat(countUsers()).isEqualTo(2);
    }

    // ---------- 欄位驗證 ----------

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("email 空白", "", "password123", "Alice", "email"),
                Arguments.of("email 格式錯誤", "not-an-email", "password123", "Alice", "email"),
                Arguments.of("email 超過 254 字", "a".repeat(250) + "@x.com", "password123", "Alice", "email"),
                Arguments.of("密碼空白", "alice@example.com", "", "Alice", "password"),
                Arguments.of("密碼少於 8 字", "alice@example.com", "1234567", "Alice", "password"),
                Arguments.of("密碼超過 72 字", "alice@example.com", "a".repeat(73), "Alice", "password"),
                Arguments.of("displayName 空白", "alice@example.com", "password123", " ", "displayName"),
                Arguments.of("displayName 超過 50 字", "alice@example.com", "password123", "a".repeat(51),
                        "displayName"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void register_invalidField_returns400AndNothingSaved(String caseName, String email, String password,
            String displayName, String invalidField) throws Exception {
        register(email, password, displayName)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // detail 的格式是「欄位: 訊息」，確認指出的是正確的欄位
                .andExpect(jsonPath("$.detail", containsString(invalidField + ":")));

        assertThat(countUsers()).isZero();
    }

    @Test
    void register_passwordExactly72Chars_isAccepted() throws Exception {
        register("alice@example.com", "a".repeat(72), "Alice").andExpect(status().isCreated());
    }

    @Test
    void register_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ not json"))
                .andExpect(status().isBadRequest());

        assertThat(countUsers()).isZero();
    }

    // ---------- helpers ----------

    private ResultActions register(String email, String password, String displayName) throws Exception {
        // 用字串組 JSON，測試內容就是實際送出的 body
        String body = """
                {"email": "%s", "password": "%s", "displayName": "%s"}
                """.formatted(email, password, displayName);
        return mockMvc.perform(post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<String> rolesOf(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT role FROM user_service.user_role WHERE user_id = ?", String.class, userId);
    }

    private int countUsers() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM user_service.users", Integer.class);
    }
}

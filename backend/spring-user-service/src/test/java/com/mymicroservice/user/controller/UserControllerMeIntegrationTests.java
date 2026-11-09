package com.mymicroservice.user.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

// GET /api/v1/users/me 的整合測試：本機沒有 Gateway，直接帶 X-User-Id 模擬 Gateway 驗簽後的請求
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserControllerMeIntegrationTests {
    private static final String ME_URL = "/api/v1/users/me";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
    }

    // ---------- 成功 ----------

    @Test
    void getMe_validUserId_returnsProfileWithoutPassword() throws Exception {
        UUID userId = registerUser("alice@example.com", "Alice");

        getMe(userId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles", containsInAnyOrder("CUSTOMER")))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void getMe_returnsOnlyTheCallersProfile() throws Exception {
        // 回傳的是 header 指定的那位，不會混到其他使用者
        registerUser("alice@example.com", "Alice");
        UUID bobId = registerUser("bob@example.com", "Bob");

        getMe(bobId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void getMe_userWithMultipleRoles_returnsAllRoles() throws Exception {
        UUID userId = registerUser("alice@example.com", "Alice");
        jdbcTemplate.update("INSERT INTO user_service.user_role (user_id, role) VALUES (?, 'ADMIN')", userId);

        getMe(userId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", containsInAnyOrder("ADMIN", "CUSTOMER")));
    }

    @Test
    void getMe_disabledUser_returnsProfileWithDisabledStatus() throws Exception {
        // access token 到期前仍可呼叫；擋停用帳號是登入與 refresh 的責任
        UUID userId = registerUser("alice@example.com", "Alice");
        jdbcTemplate.update("UPDATE user_service.users SET status = 'DISABLED' WHERE id = ?", userId);

        getMe(userId.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    @Test
    void getMe_headerWithSurroundingWhitespace_isAccepted() throws Exception {
        UUID userId = registerUser("alice@example.com", "Alice");

        getMe("  " + userId + "  ").andExpect(status().isOk());
    }

    // ---------- 沒有合法身分 ----------

    @Test
    void getMe_missingHeader_returns401() throws Exception {
        mockMvc.perform(get(ME_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "not-a-uuid", "12345" })
    void getMe_blankOrMalformedHeader_returns401(String headerValue) throws Exception {
        getMe(headerValue)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ---------- 使用者不存在 ----------

    @Test
    void getMe_unknownUserId_returns404() throws Exception {
        getMe(UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---------- helpers ----------

    // 經由註冊 API 建立使用者，回傳 userId
    private UUID registerUser(String email, String displayName) throws Exception {
        String body = """
                {"email": "%s", "password": "password123", "displayName": "%s"}
                """.formatted(email, displayName);
        String response = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private ResultActions getMe(String userIdHeader) throws Exception {
        return mockMvc.perform(get(ME_URL).header(UserContextHeaders.USER_ID, userIdHeader));
    }
}

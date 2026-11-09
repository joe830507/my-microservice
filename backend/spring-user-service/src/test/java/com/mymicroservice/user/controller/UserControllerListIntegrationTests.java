package com.mymicroservice.user.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.web.UserContextHeaders;
import com.mymicroservice.user.TestcontainersConfiguration;

// GET /api/v1/users 的整合測試：本機沒有 Gateway，直接帶 X-User-Id、X-User-Roles 模擬驗簽後的請求
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UserControllerListIntegrationTests {
    private static final String USERS_URL = "/api/v1/users";

    // 呼叫者只需要合法的身分 header，不必真的存在於 DB（user-service 信任 Gateway）
    private static final String ADMIN_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID aliceId;
    private UUID bobId;
    private UUID carolId;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");

        // 固定 created_at，讓預設排序（最新的在前）的結果可預期：carol > bob > alice
        aliceId = registerUser("alice@example.com", "Alice Wang", Instant.parse("2026-01-01T00:00:00Z"));
        bobId = registerUser("bob@example.com", "Bob Chen", Instant.parse("2026-02-01T00:00:00Z"));
        carolId = registerUser("carol@shop.test", "Carol", Instant.parse("2026-03-01T00:00:00Z"));
    }

    // ---------- 成功 ----------

    @Test
    void getUsers_admin_returnsFirstPageNewestFirst() throws Exception {
        listAsAdmin(get(USERS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email",
                        contains("carol@shop.test", "bob@example.com", "alice@example.com")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void getUsers_itemFields_includeRolesAndExcludePassword() throws Exception {
        jdbcTemplate.update("INSERT INTO user_service.user_role (user_id, role) VALUES (?, 'ADMIN')", aliceId);

        listAsAdmin(get(USERS_URL).param("keyword", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(aliceId.toString()))
                .andExpect(jsonPath("$.items[0].displayName").value("Alice Wang"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].roles", containsInAnyOrder("ADMIN", "CUSTOMER")))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].password").doesNotExist())
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
    }

    @Test
    void getUsers_pagination_returnsRequestedPage() throws Exception {
        listAsAdmin(get(USERS_URL).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email", contains("alice@example.com")))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void getUsers_pageBeyondLast_returnsEmptyItems() throws Exception {
        listAsAdmin(get(USERS_URL).param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void getUsers_sortByEmailAsc() throws Exception {
        listAsAdmin(get(USERS_URL).param("sortBy", "email").param("sortDirection", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email",
                        contains("alice@example.com", "bob@example.com", "carol@shop.test")));
    }

    // ---------- 篩選 ----------

    @Test
    void getUsers_keywordMatchesEmailCaseInsensitively() throws Exception {
        listAsAdmin(get(USERS_URL).param("keyword", "EXAMPLE.COM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email", containsInAnyOrder("alice@example.com", "bob@example.com")));
    }

    @Test
    void getUsers_keywordMatchesDisplayName() throws Exception {
        listAsAdmin(get(USERS_URL).param("keyword", "chen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email", contains("bob@example.com")));
    }

    @Test
    void getUsers_keywordWithLikeWildcards_isMatchedLiterally() throws Exception {
        // % 與 _ 不能被當成萬用字元，否則會列出全部使用者
        listAsAdmin(get(USERS_URL).param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()));
        listAsAdmin(get(USERS_URL).param("keyword", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void getUsers_filterByStatus() throws Exception {
        jdbcTemplate.update("UPDATE user_service.users SET status = 'DISABLED' WHERE id = ?", bobId);

        listAsAdmin(get(USERS_URL).param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(bobId.toString())));
    }

    @Test
    void getUsers_filterByRole() throws Exception {
        jdbcTemplate.update("INSERT INTO user_service.user_role (user_id, role) VALUES (?, 'ADMIN')", carolId);

        listAsAdmin(get(USERS_URL).param("role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id", contains(carolId.toString())))
                .andExpect(jsonPath("$.totalElements").value(1))
                // 篩選只影響「哪些人」，回傳的 roles 仍是完整的
                .andExpect(jsonPath("$.items[0].roles", containsInAnyOrder("ADMIN", "CUSTOMER")));
    }

    @Test
    void getUsers_combinedFilters() throws Exception {
        jdbcTemplate.update("UPDATE user_service.users SET status = 'DISABLED' WHERE id = ?", aliceId);

        listAsAdmin(get(USERS_URL).param("keyword", "example").param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].email", contains("bob@example.com")));
    }

    // ---------- 權限 ----------

    @Test
    void getUsers_missingUserId_returns401() throws Exception {
        mockMvc.perform(get(USERS_URL).header(UserContextHeaders.USER_ROLES, "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void getUsers_missingRolesHeader_returns403() throws Exception {
        mockMvc.perform(get(USERS_URL).header(UserContextHeaders.USER_ID, ADMIN_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "CUSTOMER", "", "admin", "ADMINISTRATOR", "SUPER_ADMIN" })
    void getUsers_notAdmin_returns403(String rolesHeader) throws Exception {
        // 小寫或只是包含 ADMIN 字樣都不算
        mockMvc.perform(get(USERS_URL)
                .header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, rolesHeader))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ADMIN", "CUSTOMER,ADMIN", "CUSTOMER, ADMIN" })
    void getUsers_rolesHeaderContainsAdmin_isAllowed(String rolesHeader) throws Exception {
        mockMvc.perform(get(USERS_URL)
                .header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, rolesHeader))
                .andExpect(status().isOk());
    }

    // ---------- 參數驗證 ----------

    static Stream<Arguments> invalidParams() {
        return Stream.of(
                Arguments.of("page", "-1"),
                Arguments.of("size", "0"),
                Arguments.of("size", "101"),
                Arguments.of("sortBy", "passwordHash"),
                Arguments.of("sortDirection", "up"),
                Arguments.of("status", "UNKNOWN"),
                Arguments.of("role", "ROOT"),
                Arguments.of("keyword", "a".repeat(255)));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidParams")
    void getUsers_invalidParam_returns400(String name, String value) throws Exception {
        listAsAdmin(get(USERS_URL).param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail", containsString(name + ":")));
    }

    // ---------- helpers ----------

    // 經由註冊 API 建立使用者，再固定 created_at，回傳 userId
    private UUID registerUser(String email, String displayName, Instant createdAt) throws Exception {
        String body = """
                {"email": "%s", "password": "password123", "displayName": "%s"}
                """.formatted(email, displayName);
        String response = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(response, "$.id"));
        jdbcTemplate.update("UPDATE user_service.users SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), id);
        return id;
    }

    private ResultActions listAsAdmin(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request
                .header(UserContextHeaders.USER_ID, ADMIN_ID)
                .header(UserContextHeaders.USER_ROLES, "ADMIN"));
    }
}

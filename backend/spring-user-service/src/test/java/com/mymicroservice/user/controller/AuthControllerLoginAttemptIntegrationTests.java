package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.user.TestcontainersConfiguration;

// 登入失敗次數限制：同一個 email 失敗 3 次後鎖住；Redis 為 Testcontainers 啟動的真實容器
@SpringBootTest(properties = {
        "app.login-attempt.max-failures=3",
        "app.login-attempt.window=15m" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerLoginAttemptIntegrationTests {
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "password123";
    private static final String KEY_PATTERN = "user-service:login-failures:*";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ---------- 鎖住 ----------

    @Test
    void login_failuresBelowLimit_stillReturn401() throws Exception {
        register(EMAIL);

        failLogin(EMAIL, 2);

        login(EMAIL, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_afterReachingLimit_returns429EvenWithCorrectPassword() throws Exception {
        register(EMAIL);
        failLogin(EMAIL, 3);

        // 鎖住期間猜中密碼也沒用
        login(EMAIL, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    @Test
    void login_whileBlocked_doesNotIssueTokens() throws Exception {
        UUID userId = register(EMAIL);
        failLogin(EMAIL, 3);

        login(EMAIL, PASSWORD).andExpect(status().isTooManyRequests());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_service.refresh_token WHERE user_id = ?", Integer.class, userId))
                .isZero();
    }

    @Test
    void login_unknownEmail_isCountedAndBlockedTheSameWay() throws Exception {
        // 不存在的 email 也會鎖，回應和存在的帳號一樣，無法用來探測 email 是否註冊
        failLogin("nobody@example.com", 3);

        login("nobody@example.com", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    @Test
    void login_blockIsPerEmail() throws Exception {
        register(EMAIL);
        register("bob@example.com");
        failLogin(EMAIL, 3);

        login("bob@example.com", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void login_emailIsCaseSensitive_likeAccountLookup() throws Exception {
        // email 區分大小寫（V1 的設計），計數也一樣
        register("Alice@example.com");
        failLogin(EMAIL, 3);

        login("Alice@example.com", PASSWORD).andExpect(status().isOk());
    }

    // ---------- 重設與到期 ----------

    @Test
    void login_success_resetsFailureCount() throws Exception {
        register(EMAIL);
        failLogin(EMAIL, 2);
        login(EMAIL, PASSWORD).andExpect(status().isOk());

        // 重新計算：再失敗 2 次仍未達上限
        failLogin(EMAIL, 2);
        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void login_disabledUserWithCorrectPassword_resetsFailureCount() throws Exception {
        UUID userId = register(EMAIL);
        failLogin(EMAIL, 2);
        jdbcTemplate.update("UPDATE user_service.users SET status = 'DISABLED' WHERE id = ?", userId);

        login(EMAIL, PASSWORD).andExpect(status().isForbidden());

        assertThat(redisTemplate.keys(KEY_PATTERN)).isEmpty();
    }

    @Test
    void login_blockLiftsWhenWindowEnds() throws Exception {
        register(EMAIL);
        failLogin(EMAIL, 3);
        login(EMAIL, PASSWORD).andExpect(status().isTooManyRequests());

        // 模擬 window 結束：key 到期被 Redis 刪除
        redisTemplate.delete(redisTemplate.keys(KEY_PATTERN));

        login(EMAIL, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void failureCounter_expiresAfterWindowAndDoesNotStoreEmail() throws Exception {
        register(EMAIL);
        failLogin(EMAIL, 1);

        Set<String> keys = redisTemplate.keys(KEY_PATTERN);
        assertThat(keys).singleElement().satisfies(key -> {
            // key 是 email 的雜湊，不含 email 原文
            assertThat(key).doesNotContain(EMAIL).hasSize("user-service:login-failures:".length() + 64);
            // 第一次失敗就設定到期時間（15 分鐘），不會留下永不過期的 key
            assertThat(redisTemplate.getExpire(key)).isBetween(890L, 900L);
        });
    }

    @Test
    void failureCounter_windowStartsAtFirstFailure() throws Exception {
        register(EMAIL);
        failLogin(EMAIL, 1);
        String key = redisTemplate.keys(KEY_PATTERN).iterator().next();
        redisTemplate.expire(key, java.time.Duration.ofSeconds(100));

        // 之後的失敗不會延長到期時間
        failLogin(EMAIL, 1);

        assertThat(redisTemplate.getExpire(key)).isLessThanOrEqualTo(100L);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("2");
    }

    // ---------- helpers ----------

    private void failLogin(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            login(email, "wrong-password").andExpect(status().isUnauthorized());
        }
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

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }
}

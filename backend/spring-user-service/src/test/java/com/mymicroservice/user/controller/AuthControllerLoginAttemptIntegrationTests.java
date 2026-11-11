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

// 登入失敗次數限制（兩層，SEC-05）：同一組（email + 來源 IP）失敗 3 次後鎖住那一組；
// 同一個 email 不分來源失敗總共 6 次後全部鎖住。沒有帶來源 IP 時所有人視為同一個來源，行為等於只依 email
// Redis 為 Testcontainers 啟動的真實容器
@SpringBootTest(properties = {
        "app.login-attempt.max-failures=3",
        "app.login-attempt.max-failures-per-email=6",
        "app.login-attempt.window=15m" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerLoginAttemptIntegrationTests {
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "password123";
    // email 總次數的 key；（email + IP）的 key 是 user-service:login-failures-by-ip:*
    private static final String KEY_PATTERN = "user-service:login-failures:*";
    private static final String ALL_KEYS_PATTERN = "user-service:login-failures*";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
        Set<String> keys = redisTemplate.keys(ALL_KEYS_PATTERN);
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

        // 密碼正確（即使帳號被停用）：清掉這個（email + 來源）那一組；email 的總次數保留（兩層鎖定，見下面的測試）
        assertThat(redisTemplate.keys("user-service:login-failures-by-ip:*")).isEmpty();
        assertThat(redisTemplate.keys(KEY_PATTERN)).hasSize(1);
    }

    @Test
    void login_blockLiftsWhenWindowEnds() throws Exception {
        register(EMAIL);
        failLogin(EMAIL, 3);
        login(EMAIL, PASSWORD).andExpect(status().isTooManyRequests());

        // 模擬 window 結束：key 到期被 Redis 刪除
        redisTemplate.delete(redisTemplate.keys(ALL_KEYS_PATTERN));

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

    // ---------- 兩層鎖定（SEC-05）----------

    @Test
    void login_attackerFromOneIp_cannotLockOutTheOwnerFromAnotherIp() throws Exception {
        register(EMAIL);
        for (int i = 0; i < 3; i++) {
            login(EMAIL, "wrong-password", "198.51.100.7").andExpect(status().isUnauthorized());
        }

        // 攻擊者這個來源被鎖住，連密碼正確也不行
        login(EMAIL, PASSWORD, "198.51.100.7").andExpect(status().isTooManyRequests());
        // 本人從別的地方登入不受影響
        login(EMAIL, PASSWORD, "203.0.113.20").andExpect(status().isOk());
    }

    @Test
    void login_failuresSpreadAcrossManyIps_eventuallyBlockEverySource() throws Exception {
        register(EMAIL);
        // 每個來源只失敗 1 次，單一來源都沒達到上限；但總共 6 次達到這個 email 的總上限
        for (int i = 1; i <= 6; i++) {
            login(EMAIL, "wrong-password", "198.51.100." + i).andExpect(status().isUnauthorized());
        }

        // 全新的來源、密碼正確也被擋：分散式暴力猜密碼的防線
        login(EMAIL, PASSWORD, "203.0.113.99").andExpect(status().isTooManyRequests());
    }

    @Test
    void login_successFromOneIp_doesNotWashAwayTheEmailTotal() throws Exception {
        register(EMAIL);
        for (int i = 1; i <= 4; i++) {
            login(EMAIL, "wrong-password", "198.51.100." + i).andExpect(status().isUnauthorized());
        }

        login(EMAIL, PASSWORD, "203.0.113.50").andExpect(status().isOk());

        // 本人成功登入只清掉自己那一組；email 的總次數還在，攻擊者累計的失敗沒有被洗掉
        String emailKey = redisTemplate.keys(KEY_PATTERN).iterator().next();
        assertThat(redisTemplate.opsForValue().get(emailKey)).isEqualTo("4");
        login(EMAIL, "wrong-password", "198.51.100.5").andExpect(status().isUnauthorized());
        login(EMAIL, "wrong-password", "198.51.100.6").andExpect(status().isUnauthorized());
        login(EMAIL, PASSWORD, "203.0.113.51").andExpect(status().isTooManyRequests());
    }

    @Test
    void failureCounterByIp_doesNotStoreEmailOrIp_andExpires() throws Exception {
        register(EMAIL);
        login(EMAIL, "wrong-password", "198.51.100.7").andExpect(status().isUnauthorized());

        Set<String> keys = redisTemplate.keys("user-service:login-failures-by-ip:*");
        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key).doesNotContain(EMAIL).doesNotContain("198.51.100.7")
                    .hasSize("user-service:login-failures-by-ip:".length() + 64);
            assertThat(redisTemplate.getExpire(key)).isBetween(890L, 900L);
        });
    }

    @Test
    void login_withForgedClientIpHeaderValueIsJustAnotherSource() throws Exception {
        // user-service 信任 Gateway 帶來的 X-Client-Ip（Gateway 會先清掉用戶端自己送的值）；
        // 直接打 user-service 時帶任意值，只是被當成另一個來源，不會讓別人的失敗次數被清掉
        register(EMAIL);
        failLogin(EMAIL, 3); // 沒帶來源：unknown 那一組被鎖

        login(EMAIL, PASSWORD, "10.0.0.1").andExpect(status().isOk());
        login(EMAIL, PASSWORD).andExpect(status().isTooManyRequests());
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
        return login(email, password, null);
    }

    // clientIp：Gateway 用 X-Client-Ip 帶來的用戶端來源 IP；null 表示沒有帶
    private ResultActions login(String email, String password, String clientIp) throws Exception {
        var request = post("/api/v1/auth/login");
        if (clientIp != null) {
            request.header("X-Client-Ip", clientIp);
        }
        return mockMvc.perform(request
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }
}

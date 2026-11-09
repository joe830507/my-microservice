package com.mymicroservice.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.user.TestcontainersConfiguration;

// 排程清除過期 refresh token；直接呼叫 run()，不等 cron 觸發
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RefreshTokenCleanupJobIntegrationTests {
    private static final String PASSWORD = "password123";

    @Autowired
    private RefreshTokenCleanupJob job;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RefreshTokenGenerator refreshTokenGenerator;
    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
    }

    @Test
    void run_deletesOnlyTokensExpiredLongerThanRetention() throws Exception {
        register("alice@example.com");
        String expiredLongAgo = login("alice@example.com");
        String expiredRecently = login("alice@example.com");
        String active = login("alice@example.com");
        String revokedNotExpired = login("alice@example.com");
        String revokedAndExpiredLongAgo = login("alice@example.com");

        setExpiresAt(expiredLongAgo, "now() - interval '8 days'");
        setExpiresAt(expiredRecently, "now() - interval '6 days'");
        revoke(revokedNotExpired);
        revoke(revokedAndExpiredLongAgo);
        setExpiresAt(revokedAndExpiredLongAgo, "now() - interval '30 days'");

        job.run();

        // retention 是 7 天：過期超過 7 天的刪掉；過期不到 7 天、仍有效、已撤銷但未過期的保留
        assertThat(remainingHashes()).containsExactlyInAnyOrder(
                hash(expiredRecently), hash(active), hash(revokedNotExpired));
    }

    @Test
    void run_deletingSuccessorClearsReplacedByOfPredecessor() throws Exception {
        // replaced_by 的 FK 是 ON DELETE SET NULL：被指向的 token 刪掉時不會擋下 DELETE
        register("alice@example.com");
        String original = login("alice@example.com");
        String rotated = refresh(original);
        setExpiresAt(rotated, "now() - interval '8 days'");

        job.run();

        assertThat(remainingHashes()).containsExactly(hash(original));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT replaced_by FROM user_service.refresh_token WHERE token_hash = ?", UUID.class,
                hash(original))).isNull();
    }

    @Test
    void run_nothingToDelete_succeeds() {
        job.run();

        assertThat(remainingHashes()).isEmpty();
    }

    @Test
    void job_isScheduledWithConfiguredCron() {
        List<String> cronExpressions = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .map(ScheduledTaskHolder::getScheduledTasks)
                .flatMap(Collection::stream)
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(task -> ((CronTask) task).getExpression())
                .toList();

        assertThat(cronExpressions).contains("0 30 3 * * *");
    }

    // ---------- helpers ----------

    private void register(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s", "displayName": "User"}
                        """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
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

    private String refresh(String refreshToken) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.refreshToken");
    }

    // sqlExpression 由測試自己寫死，不是外部輸入
    private void setExpiresAt(String refreshToken, String sqlExpression) {
        jdbcTemplate.update("UPDATE user_service.refresh_token SET expires_at = " + sqlExpression
                + " WHERE token_hash = ?", hash(refreshToken));
    }

    private void revoke(String refreshToken) {
        jdbcTemplate.update("UPDATE user_service.refresh_token SET revoked_at = now() WHERE token_hash = ?",
                hash(refreshToken));
    }

    private List<String> remainingHashes() {
        return jdbcTemplate.queryForList("SELECT token_hash FROM user_service.refresh_token", String.class);
    }

    private String hash(String refreshToken) {
        return refreshTokenGenerator.hash(refreshToken);
    }
}

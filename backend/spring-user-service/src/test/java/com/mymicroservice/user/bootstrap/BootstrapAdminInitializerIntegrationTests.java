package com.mymicroservice.user.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.common.audit.HeaderAuditorAware;
import com.mymicroservice.user.TestcontainersConfiguration;
import com.nimbusds.jose.jwk.RSAKey;

// dev profile 下的 ADMIN 初始化；啟動時已經跑過一次，每個測試先清空 DB 再手動呼叫 run()
@SpringBootTest(properties = {
        "app.bootstrap-admin.email=admin@example.com",
        "app.bootstrap-admin.password=admin-password" })
@AutoConfigureMockMvc
@ActiveProfiles({ "test", "dev" })
@Import(TestcontainersConfiguration.class)
class BootstrapAdminInitializerIntegrationTests {
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "admin-password";

    @Autowired
    private BootstrapAdminInitializer initializer;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RSAKey rsaKey;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
    }

    @Test
    void run_noExistingUser_createsActiveAdminWithHashedPassword() {
        runInitializer();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM user_service.users WHERE email = ?", ADMIN_EMAIL);
        String passwordHash = (String) row.get("password_hash");

        assertThat(passwordHash).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches(ADMIN_PASSWORD, passwordHash)).isTrue();
        assertThat(row.get("display_name")).isEqualTo("Admin");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        // 不在 HTTP 請求中，記為系統使用者
        assertThat(row.get("created_by")).isEqualTo(HeaderAuditorAware.SYSTEM_USER_ID);
        assertThat(rolesOf((UUID) row.get("id"))).containsExactly("ADMIN");
    }

    @Test
    void run_twice_createsOnlyOneAdmin() {
        runInitializer();
        runInitializer();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM user_service.users", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void run_emailAlreadyRegistered_leavesExistingUserUntouched() throws Exception {
        // 同一個 email 已經是一般會員：不改密碼、不補 ADMIN
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "customer-password", "displayName": "Customer"}
                        """.formatted(ADMIN_EMAIL)))
                .andExpect(status().isCreated());

        runInitializer();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM user_service.users WHERE email = ?", ADMIN_EMAIL);
        assertThat(passwordEncoder.matches("customer-password", (String) row.get("password_hash"))).isTrue();
        assertThat(rolesOf((UUID) row.get("id"))).containsExactly("CUSTOMER");
    }

    @Test
    void createdAdmin_canLoginAndTokenContainsAdminRole() throws Exception {
        runInitializer();

        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build()
                .decode(JsonPath.read(response, "$.accessToken"));
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
    }

    private void runInitializer() {
        initializer.run(new DefaultApplicationArguments());
    }

    private List<String> rolesOf(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT role FROM user_service.user_role WHERE user_id = ?", String.class, userId);
    }
}

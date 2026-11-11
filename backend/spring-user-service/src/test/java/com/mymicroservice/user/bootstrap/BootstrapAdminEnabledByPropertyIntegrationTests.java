package com.mymicroservice.user.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.mymicroservice.user.TestcontainersConfiguration;

// SEC-12：第一個管理員由設定開關 app.bootstrap-admin.enabled 控制，不再綁在 dev profile。
// 這裡沒有 dev profile（K8s 等環境的情境），只靠開關建立
@SpringBootTest(properties = {
        "app.bootstrap-admin.enabled=true",
        "app.bootstrap-admin.email=first-admin@example.com",
        "app.bootstrap-admin.password=first-admin-password" })
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BootstrapAdminEnabledByPropertyIntegrationTests {
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void withoutDevProfile_enabledSwitchRegistersInitializerAndCreatesAdmin() {
        assertThat(context.getBeanNamesForType(BootstrapAdminInitializer.class)).hasSize(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM user_service.users WHERE email = ?", "first-admin@example.com");

        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(passwordEncoder.matches("first-admin-password", (String) row.get("password_hash"))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_service.user_role WHERE role = 'ADMIN'", Integer.class)).isEqualTo(1);
    }
}

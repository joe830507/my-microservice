package com.mymicroservice.user.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.properties.BootstrapAdminProperties;

// 沒有 dev profile 時：不建立 ADMIN，也不要求 app.bootstrap-admin 設定（服務能正常啟動就代表沒被驗證擋下）
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BootstrapAdminNotDevIntegrationTests {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void withoutDevProfile_initializerAndPropertiesAreNotRegistered() {
        assertThat(context.getBeanNamesForType(BootstrapAdminInitializer.class)).isEmpty();
        assertThat(context.getBeanNamesForType(BootstrapAdminProperties.class)).isEmpty();
    }

    @Test
    void withoutDevProfile_noAdminIsCreated() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_service.user_role WHERE role = 'ADMIN'", Integer.class)).isZero();
    }
}

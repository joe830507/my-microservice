package com.mymicroservice.user.configs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.properties.JwtProperties;

// SEC-07：access token 無法撤銷，時效越短風險越小。預設 5 分鐘，避免有人不小心改回很長的時間而沒人發現
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccessTokenTtlIntegrationTests {
    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void accessTokenTtl_defaultsToFiveMinutes() {
        assertThat(jwtProperties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(5));
    }
}

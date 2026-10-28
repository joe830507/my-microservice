package com.mymicroservice.product.config;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    // 尚未串接登入前，一律以系統使用者記錄建立者與修改者
    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);

    @Bean
    public AuditorAware<UUID> auditorAware() {
        // TODO: 串接登入後，改為從 SecurityContext 取得目前使用者 ID
        return () -> Optional.of(SYSTEM_USER_ID);
    }
}

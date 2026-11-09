package com.mymicroservice.product.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.mymicroservice.common.CommonAutoConfiguration;

// AuditorAware 由 common 提供（HeaderAuditorAware）：有 X-User-Id 用它，否則記為系統使用者
@Configuration
@EnableJpaAuditing(auditorAwareRef = CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME)
public class JpaAuditingConfig {
}

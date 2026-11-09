package com.mymicroservice.order.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.mymicroservice.common.CommonAutoConfiguration;

@Configuration
@EnableJpaAuditing(auditorAwareRef = CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME)
public class JpaAuditingConfig {
}

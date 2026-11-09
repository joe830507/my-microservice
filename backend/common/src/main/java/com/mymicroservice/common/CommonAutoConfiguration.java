package com.mymicroservice.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;

import com.mymicroservice.common.audit.HeaderAuditorAware;
import com.mymicroservice.common.exception.GlobalExceptionHandler;
import com.mymicroservice.common.web.UserContextWebMvcConfigurer;

// 服務加入 common 依賴後自動載入，不需要調整 scanBasePackages
// 提供的 bean 一律加 @ConditionalOnMissingBean，讓服務可以自行覆寫
@AutoConfiguration
public class CommonAutoConfiguration {

    public static final String AUDITOR_AWARE_BEAN_NAME = "auditorAware";

    @Bean
    @ConditionalOnWebApplication(type = Type.SERVLET)
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    // 讓 controller 可以使用 @CurrentUserId、@RequireRoles
    @Bean
    @ConditionalOnWebApplication(type = Type.SERVLET)
    @ConditionalOnMissingBean
    public UserContextWebMvcConfigurer userContextWebMvcConfigurer() {
        return new UserContextWebMvcConfigurer();
    }

    // 服務需自行宣告 @EnableJpaAuditing(auditorAwareRef = "auditorAware")
    // 以名稱判斷：服務已有同名 bean 時不建立，避免兩個 AuditorAware 衝突
    @Bean(AUDITOR_AWARE_BEAN_NAME)
    @ConditionalOnMissingBean(name = AUDITOR_AWARE_BEAN_NAME)
    public HeaderAuditorAware auditorAware() {
        return new HeaderAuditorAware();
    }
}

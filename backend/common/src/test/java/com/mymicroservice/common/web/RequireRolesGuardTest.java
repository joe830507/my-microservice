package com.mymicroservice.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mymicroservice.common.CommonAutoConfiguration;

class RequireRolesGuardTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(GuardedService.class);

    @Test
    void startsWhenInterceptorIsRegistered() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, CommonAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsWhenCommonAutoConfigurationIsMissing() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("RequireRolesInterceptor is not registered"));
    }

    @Test
    void failsWhenServiceReplacesConfigurerWithoutInterceptor() {
        // 服務自己定義同型別的 bean，CommonAutoConfiguration 會退讓（@ConditionalOnMissingBean），interceptor 就沒了
        contextRunner
                .withUserConfiguration(ConfigurerWithoutInterceptor.class)
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, CommonAutoConfiguration.class))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("RequireRolesInterceptor is not registered"));
    }

    // 模擬服務的啟動類別
    @Configuration(proxyBeanMethods = false)
    @Import(RequireRolesGuard.class)
    static class GuardedService {
    }

    @Configuration(proxyBeanMethods = false)
    static class ConfigurerWithoutInterceptor {
        @Bean
        UserContextWebMvcConfigurer userContextWebMvcConfigurer() {
            return new UserContextWebMvcConfigurer() {
                @Override
                public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry r) {
                }
            };
        }
    }
}

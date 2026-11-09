package com.mymicroservice.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import com.mymicroservice.common.audit.HeaderAuditorAware;
import com.mymicroservice.common.exception.GlobalExceptionHandler;
import com.mymicroservice.common.web.UserContextWebMvcConfigurer;

class CommonAutoConfigurationTest {

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class));

    @Test
    void registersDefaultBeansInServletApplication() {
        webContextRunner.run(context -> {
            assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
            assertThat(context).hasSingleBean(UserContextWebMvcConfigurer.class);
            assertThat(context).getBean(CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME)
                    .isInstanceOf(HeaderAuditorAware.class);
        });
    }

    @Test
    void backsOffWhenServiceDefinesOwnAuditorAware() {
        webContextRunner.withUserConfiguration(CustomAuditorAwareConfig.class).run(context -> {
            assertThat(context).hasSingleBean(AuditorAware.class);
            assertThat(context).getBean(CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME)
                    .isNotInstanceOf(HeaderAuditorAware.class);
        });
    }

    @Test
    void skipsExceptionHandlerOutsideServletApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(GlobalExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(UserContextWebMvcConfigurer.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAuditorAwareConfig {

        @Bean(CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME)
        AuditorAware<UUID> auditorAware() {
            return () -> Optional.of(UUID.randomUUID());
        }
    }
}

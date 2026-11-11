package com.mymicroservice.observability;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

// logback-spring.xml 裡的 OTEL appender 啟動時還不認得 OpenTelemetry SDK（SDK 是 Spring 建立的）：
// SDK 建好之後要把它交給 appender，之後的 log 才會送到 collector；在這之前的 log 由 appender 暫存
// Spring Boot 只提供 OTLP 匯出的設定，不會自動做這一步
@AutoConfiguration(afterName = "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass(OpenTelemetryAppender.class)
public class OpenTelemetryLogbackAutoConfiguration {

    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    InitializingBean openTelemetryLogbackAppenderInstaller(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}

package com.mymicroservice.common.resilience;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;

// 提供 DownstreamCallers；沒有用到的服務（沒有注入）只多兩個空的 registry
@AutoConfiguration
public class ResilienceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DownstreamCallers downstreamCallers() {
        return new DownstreamCallers(CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    }

    // 有 Micrometer 與 resilience4j-micrometer 時，熔斷器與重試的狀態進指標（observability 模組帶來這些依賴）
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ MeterRegistry.class, TaggedCircuitBreakerMetrics.class })
    @ConditionalOnBean(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        ResilienceMetricsBinder resilienceMetricsBinder(DownstreamCallers callers, MeterRegistry meterRegistry) {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(callers.circuitBreakers()).bindTo(meterRegistry);
            TaggedRetryMetrics.ofRetryRegistry(callers.retries()).bindTo(meterRegistry);
            return new ResilienceMetricsBinder();
        }
    }

    // 只是讓綁定的結果有一個 bean 可以注入／檢查
    static final class ResilienceMetricsBinder {
    }
}

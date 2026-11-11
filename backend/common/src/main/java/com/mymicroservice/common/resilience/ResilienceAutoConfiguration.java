package com.mymicroservice.common.resilience;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

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
    public DownstreamCallers downstreamCallers(ObjectProvider<MeterRegistry> meterRegistry) {
        CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
        RetryRegistry retries = RetryRegistry.ofDefaults();
        meterRegistry.ifAvailable(registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(registry);
            TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(registry);
        });
        return new DownstreamCallers(circuitBreakers, retries);
    }
}

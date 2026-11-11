package com.mymicroservice.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

// 建立 DownstreamCaller 的工廠（每個下游服務一個，用名字區分；同名會拿到同一個熔斷器）
// 熔斷器與重試的狀態會出現在指標裡（resilience4j_circuitbreaker_*、resilience4j_retry_*），有 MeterRegistry 時自動綁定
public class DownstreamCallers {
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;

    public DownstreamCallers(CircuitBreakerRegistry circuitBreakers, RetryRegistry retries) {
        this.circuitBreakers = circuitBreakers;
        this.retries = retries;
    }

    public DownstreamCaller create(String name, ResilienceProperties properties) {
        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .recordException(DownstreamCaller::isFailure)
                .build();
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(properties.retryMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(properties.retryInitialBackoff(), 2.0))
                .retryOnException(DownstreamCaller::isRetryable)
                .failAfterMaxAttempts(false)
                .build();
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(name, breakerConfig);
        Retry retry = retries.retry(name, retryConfig);
        return new DownstreamCaller(breaker, retry);
    }
}

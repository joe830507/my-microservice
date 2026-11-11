package com.mymicroservice.gateway.resilience;

import java.util.concurrent.TimeoutException;

import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mymicroservice.gateway.properties.GatewayResilienceProperties;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;

// 每個路由的 CircuitBreaker filter 用自己的名字建立熔斷器，設定都來自這裡
// Spring Cloud 預設的 TimeLimiter 是 1 秒，會把正常但慢的請求（例如上傳、報表）砍掉：一定要明確設定
@Configuration
public class CircuitBreakerConfiguration {

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> gatewayCircuitBreakers(GatewayResilienceProperties properties) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(properties.slidingWindowSize())
                        .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                        .failureRateThreshold(properties.failureRateThreshold())
                        .waitDurationInOpenState(properties.waitDurationInOpenState())
                        .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(properties.timeLimit()).build())
                .build());
    }

    // 參考用：TimeoutException 會當成 504（見 FallbackController）
    static boolean isTimeout(Throwable throwable) {
        return throwable instanceof TimeoutException;
    }
}

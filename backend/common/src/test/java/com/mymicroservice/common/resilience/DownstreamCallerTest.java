package com.mymicroservice.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

class DownstreamCallerTest {
    // 4 次呼叫就評估，失敗超過一半就打開；重試 3 次、等待極短
    private static final ResilienceProperties PROPERTIES = new ResilienceProperties(4, 4, 50,
            Duration.ofMinutes(1), 1, 3, Duration.ofMillis(1));

    private DownstreamCallers callers;

    @BeforeEach
    void setUp() {
        callers = new DownstreamCallers(CircuitBreakerRegistry.ofDefaults(), RetryRegistry.ofDefaults());
    }

    private static ResourceAccessException connectRefused() {
        return new ResourceAccessException("refused", new ConnectException("Connection refused"));
    }

    private static ResourceAccessException readTimeout() {
        return new ResourceAccessException("timeout", new SocketTimeoutException("Read timed out"));
    }

    private static HttpServerErrorException serverError(HttpStatus status) {
        return HttpServerErrorException.create(status, status.name(), null, null, null);
    }

    // ---------- 重試 ----------

    @Test
    void idempotent_retriesConnectFailureUntilSuccess() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        AtomicInteger attempts = new AtomicInteger();

        String result = caller.callIdempotent(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw connectRefused();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void idempotent_retries502503504ButNot500() {
        for (HttpStatus status : new HttpStatus[] { HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE,
                HttpStatus.GATEWAY_TIMEOUT }) {
            // 每個狀態用自己的熔斷器：失敗累積多了熔斷器會打開，影響後面的案例
            DownstreamCaller caller = callers.create("a-" + status.value(), PROPERTIES);
            AtomicInteger attempts = new AtomicInteger();
            assertThat(caller.callIdempotent(() -> {
                if (attempts.incrementAndGet() == 1) {
                    throw serverError(status);
                }
                return "ok";
            })).isEqualTo("ok");
            assertThat(attempts).as(status.name()).hasValue(2);
        }

        DownstreamCaller caller = callers.create("a-500", PROPERTIES);
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> caller.callIdempotent(() -> {
            attempts.incrementAndGet();
            throw serverError(HttpStatus.INTERNAL_SERVER_ERROR);
        })).isInstanceOf(HttpServerErrorException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void idempotent_doesNotRetryReadTimeout() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> caller.callIdempotent(() -> {
            attempts.incrementAndGet();
            throw readTimeout();
        })).isInstanceOf(ResourceAccessException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void idempotent_doesNotRetryConnectTimeout() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        AtomicInteger attempts = new AtomicInteger();

        // JDK HttpClient 的連線逾時：HttpConnectTimeoutException 裡面還包著一個 ConnectException
        assertThatThrownBy(() -> caller.callIdempotent(() -> {
            attempts.incrementAndGet();
            HttpConnectTimeoutException timeout = new HttpConnectTimeoutException("HTTP connect timed out");
            timeout.initCause(new ConnectException("HTTP connect timed out"));
            throw new ResourceAccessException("connect timeout", timeout);
        })).isInstanceOf(ResourceAccessException.class);

        assertThat(attempts).hasValue(1);
    }

    @Test
    void idempotent_givesUpAfterMaxAttemptsAndRethrowsTheLastFailure() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> caller.callIdempotent(() -> {
            attempts.incrementAndGet();
            throw connectRefused();
        })).isInstanceOf(ResourceAccessException.class);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void clientErrorIsNotRetriedAndDoesNotCountAsFailure() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        AtomicInteger attempts = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> caller.callIdempotent(() -> {
                attempts.incrementAndGet();
                throw HttpClientErrorException.create(HttpStatus.CONFLICT, "conflict", null, null, null);
            })).isInstanceOf(HttpClientErrorException.class);
        }

        assertThat(attempts).hasValue(10);
        assertThat(caller.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(caller.circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void nonIdempotentCall_isNeverRetried() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> caller.call(() -> {
            attempts.incrementAndGet();
            throw connectRefused();
        })).isInstanceOf(ResourceAccessException.class);

        assertThat(attempts).hasValue(1);
    }

    // ---------- 熔斷 ----------

    @Test
    void opensAfterTheFailureRateIsExceeded_andRejectsWithoutCalling() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> caller.call(() -> {
                throw readTimeout();
            })).isInstanceOf(ResourceAccessException.class);
        }
        assertThat(caller.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> caller.call(() -> {
            calls.incrementAndGet();
            return "x";
        })).isInstanceOf(CallNotPermittedException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void idempotentCall_doesNotRetryWhenTheBreakerIsOpen() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        caller.circuitBreaker().transitionToOpenState();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> caller.callIdempotent(() -> {
            calls.incrementAndGet();
            return "x";
        })).isInstanceOf(CallNotPermittedException.class);

        assertThat(calls).hasValue(0);
    }

    @Test
    void halfOpen_closesAgainAfterASuccessfulProbe() {
        DownstreamCaller caller = callers.create("a", PROPERTIES);
        caller.circuitBreaker().transitionToOpenState();
        caller.circuitBreaker().transitionToHalfOpenState();

        assertThat(caller.call(() -> "ok")).isEqualTo("ok");

        assertThat(caller.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void breakersAreIndependentPerName() {
        DownstreamCaller first = callers.create("first", PROPERTIES);
        DownstreamCaller second = callers.create("second", PROPERTIES);
        first.circuitBreaker().transitionToOpenState();

        assertThat(second.call(() -> "ok")).isEqualTo("ok");
    }
}

package com.mymicroservice.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.mymicroservice.user.properties.LoginAttemptProperties;

// Redis 無法連線時的行為（fail open）：整合測試很難讓容器剛好斷線，用 mock 模擬
class LoginAttemptServiceTest {
    private static final RedisConnectionFailureException REDIS_DOWN =
            new RedisConnectionFailureException("connection refused");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final LoginAttemptService service = new LoginAttemptService(redisTemplate,
            new LoginAttemptProperties(5, 30, Duration.ofMinutes(15)));

    @Test
    @SuppressWarnings("unchecked")
    void isBlocked_redisDown_allowsLogin() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(REDIS_DOWN);

        assertThat(service.isBlocked("alice@example.com", "203.0.113.1")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordFailure_redisDown_doesNotThrow() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(REDIS_DOWN);

        assertThatCode(() -> service.recordFailure("alice@example.com", "203.0.113.1")).doesNotThrowAnyException();
    }

    @Test
    void reset_redisDown_doesNotThrow() {
        when(redisTemplate.delete(anyString())).thenThrow(REDIS_DOWN);

        assertThatCode(() -> service.reset("alice@example.com", "203.0.113.1")).doesNotThrowAnyException();
    }

    @Test
    void key_isStableHashOfEmail() {
        assertThat(LoginAttemptService.key("alice@example.com"))
                .isEqualTo(LoginAttemptService.key("alice@example.com"))
                .isNotEqualTo(LoginAttemptService.key("Alice@example.com"))
                .startsWith("user-service:login-failures:")
                .doesNotContain("alice");
    }
}

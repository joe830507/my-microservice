package com.mymicroservice.user.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.mymicroservice.user.properties.LoginAttemptProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 以 email 計算登入失敗次數，防止對同一個帳號暴力猜密碼
// 計數放在 Redis：多個實例共用，而且到期自動消失
//
// 只依 email 限制，代價是知道 email 的人可以故意失敗把對方鎖住一段時間（window）；
// 依來源 IP 的限制要在 Gateway 做（user-service 在 Gateway 後面，看到的都是 Gateway 的 IP）
//
// Redis 無法連線時一律放行（fail open）並記 warn：寧可暫時沒有限制，也不要讓所有人都無法登入
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptService {
    private static final String KEY_PREFIX = "user-service:login-failures:";

    // INCR 和設定到期時間要一起完成：分兩個指令的話，中間失敗會留下永不過期的 key，帳號就永久被鎖
    // 只在第一次失敗時設定到期時間，形成從第一次失敗起算的固定時間窗
    private static final RedisScript<Long> INCREMENT_SCRIPT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginAttemptProperties properties;

    // 失敗次數已達上限：在比對密碼之前呼叫，鎖住期間就算密碼正確也不放行
    public boolean isBlocked(String email) {
        try {
            String count = redisTemplate.opsForValue().get(key(email));
            return count != null && Long.parseLong(count) >= properties.maxFailures();
        } catch (DataAccessException exception) {
            log.warn("login attempt check skipped, redis unavailable: {}", exception.getMessage());
            return false;
        }
    }

    public void recordFailure(String email) {
        try {
            redisTemplate.execute(INCREMENT_SCRIPT, List.of(key(email)),
                    String.valueOf(properties.window().toMillis()));
        } catch (DataAccessException exception) {
            log.warn("login failure not recorded, redis unavailable: {}", exception.getMessage());
        }
    }

    // 登入成功後清除，之前的失敗不再累計
    public void reset(String email) {
        try {
            redisTemplate.delete(key(email));
        } catch (DataAccessException exception) {
            log.warn("login failures not reset, redis unavailable: {}", exception.getMessage());
        }
    }

    // key 用 email 的 SHA-256，不把 email 原文放進 Redis；email 區分大小寫，和登入查詢一致
    static String key(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

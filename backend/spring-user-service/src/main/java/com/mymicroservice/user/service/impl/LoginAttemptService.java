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

// 計算登入失敗次數，防止暴力猜密碼；兩層鎖定（SEC-05）：
//   1. （email + 來源 IP）：同一組在 window 內失敗 maxFailures 次就鎖住那一組。單一攻擊者來源只鎖得到自己，
//      鎖不到本人從別處登入——原本只依 email，知道 email 的人故意失敗就能把本人鎖住
//   2. email 總次數：同一個 email 不分來源的失敗總數達 maxFailuresPerEmail 就全部鎖住，擋分散在很多 IP 的暴力猜密碼
//      （每個來源在 Gateway 還有登入限流，要湊到這個數字需要很多個來源）
// 來源 IP 由 Gateway 用 X-Client-Ip 帶過來；沒有時（例如直接呼叫、測試）所有人視為同一個來源 "unknown"，行為等於只依 email
// 計數放在 Redis：多個實例共用，而且到期自動消失
//
// Redis 無法連線時一律放行（fail open）並記 warn：寧可暫時沒有限制，也不要讓所有人都無法登入
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptService {
    // email 總次數（舊的 key 格式，維持不變）與（email + IP）各一組
    private static final String KEY_PREFIX = "user-service:login-failures:";
    private static final String PAIR_KEY_PREFIX = "user-service:login-failures-by-ip:";
    private static final String UNKNOWN_IP = "unknown";

    // INCR 和設定到期時間要一起完成：分兩個指令的話，中間失敗會留下永不過期的 key，帳號就永久被鎖
    // 只在第一次失敗時設定到期時間，形成從第一次失敗起算的固定時間窗
    private static final RedisScript<Long> INCREMENT_SCRIPT = RedisScript.of("""
            for i = 1, #KEYS do
                local count = redis.call('INCR', KEYS[i])
                if count == 1 then redis.call('PEXPIRE', KEYS[i], ARGV[1]) end
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final LoginAttemptProperties properties;

    // 失敗次數已達上限（任何一層）：在比對密碼之前呼叫，鎖住期間就算密碼正確也不放行
    public boolean isBlocked(String email, String clientIp) {
        try {
            List<String> counts = redisTemplate.opsForValue().multiGet(List.of(pairKey(email, clientIp), key(email)));
            return counts != null && (reached(counts.get(0), properties.maxFailures())
                    || reached(counts.get(1), properties.maxFailuresPerEmail()));
        } catch (DataAccessException exception) {
            log.warn("login attempt check skipped, redis unavailable: {}", exception.getMessage());
            return false;
        }
    }

    private static boolean reached(String count, int limit) {
        return count != null && Long.parseLong(count) >= limit;
    }

    // 兩層都累計
    public void recordFailure(String email, String clientIp) {
        try {
            String windowMillis = String.valueOf(properties.window().toMillis());
            redisTemplate.execute(INCREMENT_SCRIPT, List.of(pairKey(email, clientIp), key(email)), windowMillis);
        } catch (DataAccessException exception) {
            log.warn("login failure not recorded, redis unavailable: {}", exception.getMessage());
        }
    }

    // 登入成功後只清除（email + IP）這一組；email 的總次數不清：不然攻擊者的失敗會被本人成功登入一次就洗掉
    public void reset(String email, String clientIp) {
        try {
            redisTemplate.delete(pairKey(email, clientIp));
        } catch (DataAccessException exception) {
            log.warn("login failures not reset, redis unavailable: {}", exception.getMessage());
        }
    }

    // （email + 來源 IP）的 key：email 與 IP 一起雜湊，不把 email 或 IP 原文放進 Redis
    static String pairKey(String email, String clientIp) {
        String ip = clientIp == null || clientIp.isBlank() ? UNKNOWN_IP : clientIp.trim();
        // 用換行分隔 email 與 IP：email 不可能含換行，不會因為串接而讓不同的（email, IP）組合得到同一個 key
        return PAIR_KEY_PREFIX + sha256(email + '\n' + ip);
    }

    // key 用 email 的 SHA-256，不把 email 原文放進 Redis；email 區分大小寫，和登入查詢一致
    static String key(String email) {
        return KEY_PREFIX + sha256(email);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

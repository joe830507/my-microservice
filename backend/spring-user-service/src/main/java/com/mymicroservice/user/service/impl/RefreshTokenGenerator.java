package com.mymicroservice.user.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

// 產生 refresh token 與計算雜湊值
// 32 bytes 亂數（256 bits）無法被猜中，所以用 SHA-256 存就夠，不需要 bcrypt 這類慢速雜湊；
// 也因為要用雜湊值查 DB，必須是固定結果的雜湊，不能加 salt
@Component
public class RefreshTokenGenerator {
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    // base64url、不補 =，可以直接放進 JSON 或 URL，長度 43 字元
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // SHA-256 的 hex，64 字元，對應 refresh_token.token_hash
    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // 每個 Java 實作都必須支援 SHA-256，不會發生
            throw new IllegalStateException(exception);
        }
    }
}

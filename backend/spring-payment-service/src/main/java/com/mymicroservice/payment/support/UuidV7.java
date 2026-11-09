package com.mymicroservice.payment.support;

import java.security.SecureRandom;
import java.util.UUID;

// 產生 UUIDv7（RFC 9562）：前 48 bits 是毫秒時間戳，依建立時間大致遞增，當主鍵時 B-tree 索引比較不會分裂
public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        long millis = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            bytes[i] = (byte) (millis >>> (40 - 8 * i));
        }
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70); // version 7
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80); // variant 10
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) {
            most = (most << 8) | (bytes[i] & 0xff);
            least = (least << 8) | (bytes[i + 8] & 0xff);
        }
        return new UUID(most, least);
    }
}

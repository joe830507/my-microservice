package com.mymicroservice.common.events;

import java.security.SecureRandom;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();
        long most = (timestamp << 16) | 0x7000L | (RANDOM.nextInt(1 << 12));
        long least = RANDOM.nextLong();
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }
}

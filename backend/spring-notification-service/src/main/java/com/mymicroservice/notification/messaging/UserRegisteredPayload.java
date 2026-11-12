package com.mymicroservice.notification.messaging;

public record UserRegisteredPayload(String userId, String email, String displayName) {
}

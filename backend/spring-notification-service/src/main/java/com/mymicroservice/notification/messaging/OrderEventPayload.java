package com.mymicroservice.notification.messaging;

import java.math.BigDecimal;

public record OrderEventPayload(String orderId, String userId, BigDecimal totalAmount, String reason) {
}

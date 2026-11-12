package com.mymicroservice.notification.messaging;

import java.math.BigDecimal;

public record PaymentEventPayload(String paymentId, String orderId, String status, BigDecimal amount) {
}

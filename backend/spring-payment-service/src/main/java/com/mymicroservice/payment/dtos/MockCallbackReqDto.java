package com.mymicroservice.payment.dtos;

import com.mymicroservice.payment.enums.MockPaymentResult;

import jakarta.validation.constraints.NotNull;

public record MockCallbackReqDto(
        @NotNull MockPaymentResult result) {
}

package com.mymicroservice.payment.service;

import java.util.UUID;

import com.mymicroservice.payment.dtos.CreatePaymentReqDto;
import com.mymicroservice.payment.dtos.CreatePaymentResult;
import com.mymicroservice.payment.dtos.PaymentResDto;

public interface PaymentService {

    public CreatePaymentResult createPayment(UUID userId, CreatePaymentReqDto createPaymentReqDto);

    public PaymentResDto getPayment(UUID paymentId, UUID userId, boolean admin);
}

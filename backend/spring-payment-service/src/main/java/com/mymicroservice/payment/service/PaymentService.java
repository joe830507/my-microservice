package com.mymicroservice.payment.service;

import java.util.UUID;

import com.mymicroservice.payment.dtos.CreatePaymentReqDto;
import com.mymicroservice.payment.dtos.CreatePaymentResult;

public interface PaymentService {

    public CreatePaymentResult createPayment(UUID userId, CreatePaymentReqDto createPaymentReqDto);
}

package com.mymicroservice.payment.service;

import java.util.UUID;

import com.mymicroservice.payment.dtos.CreatePaymentReqDto;
import com.mymicroservice.payment.dtos.CreatePaymentResult;
import com.mymicroservice.payment.dtos.MockCallbackReqDto;
import com.mymicroservice.payment.dtos.PaymentResDto;

public interface PaymentService {

    public CreatePaymentResult createPayment(UUID userId, CreatePaymentReqDto createPaymentReqDto);

    public PaymentResDto getPayment(UUID paymentId, UUID userId, boolean admin);

    public PaymentResDto mockCallback(UUID paymentId, UUID userId, boolean admin, MockCallbackReqDto mockCallbackReqDto);

    // 排程用：重新通知 order-service；回傳是否已經有結果（NOTIFIED 或 REJECTED）
    public boolean retryOrderNotification(UUID paymentId);
}

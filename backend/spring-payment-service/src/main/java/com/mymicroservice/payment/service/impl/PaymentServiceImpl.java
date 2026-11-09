package com.mymicroservice.payment.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.payment.clients.OrderClient;
import com.mymicroservice.payment.dtos.CreatePaymentReqDto;
import com.mymicroservice.payment.dtos.CreatePaymentResult;
import com.mymicroservice.payment.dtos.PaymentResDto;
import com.mymicroservice.payment.entities.Payment;
import com.mymicroservice.payment.enums.PaymentStatus;
import com.mymicroservice.payment.exception.PaymentErrorCode;
import com.mymicroservice.payment.repositories.PaymentRepository;
import com.mymicroservice.payment.service.PaymentService;
import com.mymicroservice.payment.support.UuidV7;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private static final List<PaymentStatus> ACTIVE = List.of(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED);

    private final PaymentRepository paymentRepository;
    private final OrderClient orderClient;

    // 不加 @Transactional：呼叫 order-service 時不佔著 DB 連線
    // 兩個請求同時建立：後到的在存檔時被 uq_payment_order_active 擋下 → 409 DATA_CONFLICT，重試就會拿到已建立的那筆
    @Override
    public CreatePaymentResult createPayment(UUID userId, CreatePaymentReqDto createPaymentReqDto) {
        UUID orderId = createPaymentReqDto.orderId();
        OrderClient.Order order = orderClient.findOrder(orderId)
                .filter(found -> found.userId().equals(userId))
                .orElseThrow(() -> new AppException(PaymentErrorCode.ORDER_NOT_FOUND, orderId));

        Optional<Payment> active = paymentRepository.findFirstByOrderIdAndStatusIn(orderId, ACTIVE);
        if (active.isPresent() && active.get().getStatus() == PaymentStatus.SUCCEEDED) {
            throw new AppException(PaymentErrorCode.ORDER_ALREADY_PAID, orderId);
        }
        if (!order.isPendingPayment()) {
            throw new AppException(PaymentErrorCode.ORDER_NOT_PAYABLE, orderId, order.status());
        }
        // 已經有進行中的付款（例如使用者重新整理頁面）：回那一筆，不重複建立
        if (active.isPresent()) {
            return new CreatePaymentResult(false, PaymentResDto.from(active.get()));
        }

        Payment payment = new Payment();
        payment.setId(UuidV7.generate());
        payment.setOrderId(orderId);
        payment.setUserId(userId);
        payment.setAmount(order.totalAmount());
        payment.setStatus(PaymentStatus.PENDING);
        paymentRepository.saveAndFlush(payment);
        return new CreatePaymentResult(true, PaymentResDto.from(payment));
    }

    // 本人或 ADMIN；別人的付款和不存在一樣回 404
    @Override
    @Transactional(readOnly = true)
    public PaymentResDto getPayment(UUID paymentId, UUID userId, boolean admin) {
        return PaymentResDto.from(findVisiblePayment(paymentId, userId, admin));
    }

    private Payment findVisiblePayment(UUID paymentId, UUID userId, boolean admin) {
        return paymentRepository.findById(paymentId)
                .filter(payment -> admin || payment.getUserId().equals(userId))
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_NOT_FOUND, paymentId));
    }
}

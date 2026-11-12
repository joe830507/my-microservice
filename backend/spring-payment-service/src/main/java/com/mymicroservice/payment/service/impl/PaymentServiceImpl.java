package com.mymicroservice.payment.service.impl;

import java.util.Map;
import io.micrometer.core.instrument.Metrics;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.CommonAutoConfiguration;
import com.mymicroservice.common.events.OutboxEventWriter;
import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.payment.clients.OrderClient;
import com.mymicroservice.payment.dtos.CreatePaymentReqDto;
import com.mymicroservice.payment.dtos.CreatePaymentResult;
import com.mymicroservice.payment.dtos.MockCallbackReqDto;
import com.mymicroservice.payment.dtos.PaymentResDto;
import com.mymicroservice.payment.entities.Payment;
import com.mymicroservice.payment.enums.MockPaymentResult;
import com.mymicroservice.payment.enums.PaymentStatus;
import com.mymicroservice.payment.exception.PaymentErrorCode;
import com.mymicroservice.payment.repositories.PaymentRepository;
import com.mymicroservice.payment.service.PaymentService;
import com.mymicroservice.common.events.UuidV7;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PaymentServiceImpl implements PaymentService {
    private static final List<PaymentStatus> ACTIVE = List.of(PaymentStatus.PENDING, PaymentStatus.SUCCEEDED);

    private final PaymentRepository paymentRepository;
    private final OrderClient orderClient;
    private final AuditorAware<UUID> auditorAware;
    private final OutboxEventWriter outbox;

    public PaymentServiceImpl(PaymentRepository paymentRepository, OrderClient orderClient,
            @Qualifier(CommonAutoConfiguration.AUDITOR_AWARE_BEAN_NAME) AuditorAware<UUID> auditorAware,
            OutboxEventWriter outbox) {
        this.paymentRepository = paymentRepository;
        this.orderClient = orderClient;
        this.auditorAware = auditorAware;
        this.outbox = outbox;
    }

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

    // 模擬金流回呼：PENDING → SUCCEEDED 或 FAILED；同樣的結果重送回 200，相反的結果回 409
    @Override
    @Transactional
    public PaymentResDto mockCallback(UUID paymentId, UUID userId, boolean admin,
            MockCallbackReqDto mockCallbackReqDto) {
        Payment payment = findVisiblePayment(paymentId, userId, admin);
        boolean success = mockCallbackReqDto.result() == MockPaymentResult.SUCCESS;
        PaymentStatus target = success ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;

        boolean completed = paymentRepository.completeIfPending(paymentId, target,
                Instant.now(),
                auditorAware.getCurrentAuditor().orElseThrow()) == 1;
        if (completed) {
            Metrics.counter("payments.completed", "result", success ? "succeeded" : "failed").increment();
        } else {
            payment = paymentRepository.findById(paymentId).orElseThrow();
            if (payment.getStatus() != target) {
                throw new AppException(PaymentErrorCode.PAYMENT_ALREADY_COMPLETED, paymentId, payment.getStatus());
            }
        }
        if (completed) {
            Payment current = paymentRepository.findById(paymentId).orElseThrow();
            outbox.append("payment.events", success ? "PaymentSucceeded" : "PaymentFailed",
                    current.getOrderId().toString(), Map.of("paymentId", paymentId.toString(),
                            "orderId", current.getOrderId().toString(), "status", target.name(),
                            "amount", current.getAmount()));
        }
        return PaymentResDto.from(paymentRepository.findById(paymentId).orElseThrow());
    }

    private Payment findVisiblePayment(UUID paymentId, UUID userId, boolean admin) {
        return paymentRepository.findById(paymentId)
                .filter(payment -> admin || payment.getUserId().equals(userId))
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYMENT_NOT_FOUND, paymentId));
    }
}

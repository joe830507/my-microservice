package com.mymicroservice.payment.dtos;

// service 回給 controller 的結果：created 決定回 201 還是 200（已有進行中的付款），不會出現在回應 body
public record CreatePaymentResult(
        boolean created,

        PaymentResDto payment) {
}

package com.mymicroservice.payment.enums;

// 付款成功後通知 order-service 的結果；只有 SUCCEEDED 的付款才有值
//   PENDING   還沒通知成功（連不上、5xx），由排程重試
//   DONE      order-service 已改成 PAID
//   REJECTED  order-service 回訂單已取消：錢收了但訂單不成立，要退款（雛型階段只記錄）
public enum OrderNotification {
    PENDING,
    DONE,
    REJECTED
}

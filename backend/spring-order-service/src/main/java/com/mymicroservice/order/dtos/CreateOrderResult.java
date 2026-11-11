package com.mymicroservice.order.dtos;

// 建立訂單的結果：created = false 代表這是冪等重送（同一個 Idempotency-Key 與相同內容），回的是原本那一張
public record CreateOrderResult(OrderResDto order, boolean created) {
}

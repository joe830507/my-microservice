package com.mymicroservice.order.enums;

// inventory 那邊這張訂單的預扣「應該要是」什麼狀態，名稱和 inventory-service 一致
// 訂單取消 → RELEASED、付款成功 → CONFIRMED；實際呼叫 inventory 成功後 Order.reservationSynced 才會是 true
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}

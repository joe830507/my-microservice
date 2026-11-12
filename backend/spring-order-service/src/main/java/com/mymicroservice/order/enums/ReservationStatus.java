package com.mymicroservice.order.enums;

// inventory 那邊這張訂單的預扣「應該要是」什麼狀態，名稱和 inventory-service 一致
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}

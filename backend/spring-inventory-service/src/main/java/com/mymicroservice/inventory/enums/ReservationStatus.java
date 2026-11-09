package com.mymicroservice.inventory.enums;

// 預扣的狀態；全有全無，所以狀態屬於整張訂單
//   RESERVED → CONFIRMED（付款成功）
//   RESERVED → RELEASED（取消或逾時）
// CONFIRMED、RELEASED 都是終點，不會再變
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}

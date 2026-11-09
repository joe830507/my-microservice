package com.mymicroservice.inventory.dtos.reservation;

import java.util.List;
import java.util.UUID;

import com.mymicroservice.inventory.entities.Reservation;
import com.mymicroservice.inventory.enums.ReservationStatus;

public record ReservationResDto(
        UUID orderId,

        ReservationStatus status,

        // 依 SKU 排序，重複的 SKU 已合併
        List<Item> items) {

    public record Item(String sku, int qty) {
    }

    public static ReservationResDto from(Reservation reservation) {
        return new ReservationResDto(
                reservation.getOrderId(),
                reservation.getStatus(),
                reservation.getItems().stream().map(item -> new Item(item.getSku(), item.getQty())).toList());
    }
}

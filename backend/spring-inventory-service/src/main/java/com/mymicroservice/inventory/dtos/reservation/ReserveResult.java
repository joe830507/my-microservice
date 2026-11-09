package com.mymicroservice.inventory.dtos.reservation;

// service 回給 controller 的結果：created 決定回 201 還是 200（重送），不會出現在回應 body
public record ReserveResult(
        boolean created,

        ReservationResDto reservation) {
}

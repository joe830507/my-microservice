package com.mymicroservice.inventory.dtos.stock;

// service 回給 controller 的結果：created 決定回 201 還是 200，不會出現在回應 body
public record SetStockResult(
        boolean created,

        StockResDto stock) {
}

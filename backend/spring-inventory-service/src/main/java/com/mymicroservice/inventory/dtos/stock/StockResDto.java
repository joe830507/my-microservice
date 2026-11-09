package com.mymicroservice.inventory.dtos.stock;

import java.time.Instant;

import com.mymicroservice.inventory.entities.Stock;

public record StockResDto(
        String sku,

        // 可賣的數量，不含已預扣的；前端判斷是否可下單看這個值
        int available,

        // 已預扣、等待付款的數量
        int reserved,

        Instant updatedAt) {

    public static StockResDto from(Stock stock) {
        return new StockResDto(stock.getSku(), stock.getAvailable(), stock.getReserved(), stock.getUpdatedAt());
    }
}

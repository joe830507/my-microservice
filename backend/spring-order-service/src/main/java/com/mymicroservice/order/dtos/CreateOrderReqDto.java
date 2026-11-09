package com.mymicroservice.order.dtos;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 只接受商品與數量；名稱、價格一律以 product-service 查回的為準，不接受前端傳入
public record CreateOrderReqDto(
        // 同一個商品出現多次時合併數量
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items) {

    public record Item(
            @NotNull UUID productId,

            @NotNull @Min(1) @Max(99) Integer qty) {
    }
}

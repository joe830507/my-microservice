package com.mymicroservice.inventory.dtos.reservation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// order-service 建單時送來；orderId 是冪等鍵，重送同一筆不會重複扣
public record ReserveReqDto(
        @NotNull UUID orderId,

        // 同一個 SKU 出現多次時合併數量
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items) {

    public record Item(
            @NotBlank @Size(max = 64) String sku,

            // 上限避免 100 筆合併後超過 int 範圍
            @NotNull @Min(1) @Max(1_000_000) Integer qty) {
    }
}

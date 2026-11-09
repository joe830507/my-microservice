package com.mymicroservice.inventory.dtos.stock;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

// 後台盤點後直接設定可賣數量；reserved 由預扣流程管理，不接受從這裡修改
public record SetStockReqDto(
        @NotNull @PositiveOrZero Integer available) {
}

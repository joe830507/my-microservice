package com.mymicroservice.product.dto.product.changeProductStatus;

import jakarta.validation.constraints.NotNull;

// 目標狀態由端點決定（publish / unpublish / archive），請求只需帶 version
public record ChangeProductStatusReq(
        // 樂觀鎖，需與目前 DB 中的版本一致才可變更
        @NotNull Long version) {

}

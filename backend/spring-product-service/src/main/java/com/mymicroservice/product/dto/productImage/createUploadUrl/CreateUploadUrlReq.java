package com.mymicroservice.product.dto.productImage.createUploadUrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 允許的類型與大小上限在 service 檢查（設定檔 storage.allowed-content-types、storage.max-size）
public record CreateUploadUrlReq(
        @NotBlank @Size(max = 100) String contentType,

        // 檔案大小（bytes），會簽進上傳網址
        @NotNull @Positive Long contentLength) {
}

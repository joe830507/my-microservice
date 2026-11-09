package com.mymicroservice.product.dto.productImage.createUploadUrl;

import java.time.Instant;
import java.util.Map;

import com.mymicroservice.product.storage.PresignedUpload;

public record CreateUploadUrlRes(
        // 上傳完成後，用這個 key 呼叫登記圖片 API
        String objectKey,

        // 用 PUT 上傳檔案本體
        String uploadUrl,

        // PUT 時必須帶上的 header，否則簽章不符
        Map<String, String> requiredHeaders,

        Instant expiresAt) {

    public static CreateUploadUrlRes from(PresignedUpload upload) {
        return new CreateUploadUrlRes(upload.objectKey(), upload.uploadUrl(), upload.requiredHeaders(),
                upload.expiresAt());
    }
}

package com.mymicroservice.product.storage;

import java.time.Instant;
import java.util.Map;

// 給前端直接上傳用的簽名網址；PUT 時必須帶上 requiredHeaders，否則簽章不符
public record PresignedUpload(
        String objectKey,

        String uploadUrl,

        Map<String, String> requiredHeaders,

        Instant expiresAt) {
}

package com.mymicroservice.product.storage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mymicroservice.product.config.StorageProperties;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

// 商品圖片的物件儲存操作；service 只透過這裡碰 S3 SDK，之後換實作只改一處
// object key 的規則：
//   tmp/products/{productId}/{uuid}.{ext}   剛上傳、還沒登記；私有，一天後自動刪除
//   products/{productId}/{uuid}.{ext}       已登記；匿名可讀
@Component
@RequiredArgsConstructor
public class ImageStorage {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    // 產生上傳網址；contentType、contentLength 都簽進網址，前端無法改傳其他類型或大小
    // 副檔名由 contentType 決定，不採用前端提供的檔名
    // 呼叫前要先檢查 contentType 在允許清單內（StorageProperties.allowedContentTypes）
    public PresignedUpload presignUpload(UUID productId, String contentType, long contentLength) {
        String extension = EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("no file extension for content type " + contentType);
        }
        String objectKey = "tmp/products/" + productId + "/" + UUID.randomUUID() + "." + extension;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(request -> request
                .signatureDuration(properties.uploadUrlTtl())
                .putObjectRequest(putObjectRequest));

        Map<String, String> requiredHeaders = Map.of(
                "Content-Type", contentType,
                "Content-Length", String.valueOf(contentLength));
        return new PresignedUpload(objectKey, presigned.url().toString(), requiredHeaders,
                presigned.expiration() != null ? presigned.expiration() : Instant.now().plus(properties.uploadUrlTtl()));
    }

    // 對外的網址：前綴 + object key；之後掛 CDN 只要改 public-base-url
    public String publicUrl(String objectKey) {
        return properties.publicBaseUrl() + "/" + objectKey;
    }
}

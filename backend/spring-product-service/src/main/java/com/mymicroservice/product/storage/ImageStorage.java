package com.mymicroservice.product.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mymicroservice.product.config.StorageProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

// 商品圖片的物件儲存操作；service 只透過這裡碰 S3 SDK，之後換實作只改一處
// object key 的規則：
//   tmp/products/{productId}/{uuid}.{ext}   剛上傳、還沒登記；私有，一天後自動刪除
//   products/{productId}/{uuid}.{ext}       已登記；匿名可讀
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageStorage {
    public static final String TMP_PREFIX = "tmp/products/";

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final S3Client s3Client;
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
        String objectKey = TMP_PREFIX + productId + "/" + UUID.randomUUID() + "." + extension;

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

    // 已上傳檔案的實際類型與大小；不存在回 empty
    public Optional<StoredObject> head(String objectKey) {
        try {
            HeadObjectResponse head = s3Client.headObject(request -> request.bucket(properties.bucket()).key(objectKey));
            return Optional.of(new StoredObject(head.contentType(), head.contentLength()));
        } catch (NoSuchKeyException notFound) {
            return Optional.empty();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    // 登記：tmp/products/... → products/...（複製後刪掉 tmp），回傳新的 key
    // tmp 的刪除失敗不影響結果：tmp/ 一天後會自動刪除
    public String promote(String tmpObjectKey) {
        if (!tmpObjectKey.startsWith(TMP_PREFIX)) {
            throw new IllegalArgumentException("not a tmp object key: " + tmpObjectKey);
        }
        // 去掉開頭的 tmp/：tmp/products/{productId}/x.webp → products/{productId}/x.webp
        String objectKey = tmpObjectKey.substring("tmp/".length());
        s3Client.copyObject(request -> request
                .sourceBucket(properties.bucket()).sourceKey(tmpObjectKey)
                .destinationBucket(properties.bucket()).destinationKey(objectKey));
        try {
            delete(tmpObjectKey);
        } catch (RuntimeException exception) {
            log.warn("failed to delete promoted tmp object {}", tmpObjectKey, exception);
        }
        return objectKey;
    }

    public void delete(String objectKey) {
        s3Client.deleteObject(request -> request.bucket(properties.bucket()).key(objectKey));
    }

    public record StoredObject(String contentType, long contentLength) {
    }
}

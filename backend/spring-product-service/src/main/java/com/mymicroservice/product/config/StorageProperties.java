package com.mymicroservice.product.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

// 對應 application.yml 的 storage：商品圖片的物件儲存（相容 S3）
@Validated
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        // product-service 連物件儲存的位址
        @NotNull URI endpoint,

        // 圖片對外的網址前綴（bucket 這一層）；之後掛 CDN 只要改這裡
        @NotBlank String publicBaseUrl,

        @NotBlank String bucket,

        // 相容 S3 的服務大多不在意 region，但 SDK 簽章需要
        @NotBlank String region,

        @NotBlank String accessKey,

        @NotBlank String secretKey,

        // 上傳網址的有效時間
        @NotNull Duration uploadUrlTtl,

        // 單張圖片大小上限
        @NotNull DataSize maxSize,

        // 允許的圖片類型
        @NotEmpty List<String> allowedContentTypes) {
}

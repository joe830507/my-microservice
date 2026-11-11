package com.mymicroservice.product.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;
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
        // product-service 連物件儲存的位址（叢集內部連得到的位址，例如 K8s 的 Service 名稱）
        @NotNull URI endpoint,
        // 給瀏覽器直接上傳用的位址，只用在簽章網址（S3Presigner）；沒設定就沿用 endpoint
        // 瀏覽器連不到叢集內部的位址，所以內部位址與對外位址不同時（例如 K8s）要設定這個；
        // 簽章包含 Host，這個值要和瀏覽器實際連線的主機與埠完全一致
        @Nullable URI uploadEndpoint,

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

    // 產生上傳簽章網址用的位址
    public URI presignEndpoint() {
        return uploadEndpoint != null ? uploadEndpoint : endpoint;
    }
}

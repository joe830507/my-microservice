package com.mymicroservice.product.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.mymicroservice.product.TestcontainersConfiguration;
import com.mymicroservice.product.config.StorageProperties;

// 沒有設定 storage.upload-endpoint：上傳簽章網址用 storage.endpoint（原本的行為不變）
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PresignUploadEndpointDefaultIntegrationTests {
    @Autowired
    private ImageStorage imageStorage;
    @Autowired
    private StorageProperties properties;

    @Test
    void presignedUrl_usesEndpoint() {
        assertThat(properties.uploadEndpoint()).isNull();
        assertThat(properties.presignEndpoint()).isEqualTo(properties.endpoint());

        URI url = URI.create(imageStorage.presignUpload(UUID.randomUUID(), "image/png", 100).uploadUrl());

        assertThat(url.getHost()).isEqualTo(properties.endpoint().getHost());
        assertThat(url.getPort()).isEqualTo(properties.endpoint().getPort());
    }
}
